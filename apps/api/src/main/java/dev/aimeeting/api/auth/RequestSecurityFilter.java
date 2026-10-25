package dev.aimeeting.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.config.AppProperties;
import dev.aimeeting.api.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSecurityFilter extends OncePerRequestFilter {
    private static final int LOGIN_ATTEMPTS_PER_MINUTE = 10;
    private final SessionService sessions;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, LoginWindow> loginWindows = new ConcurrentHashMap<>();

    public RequestSecurityFilter(SessionService sessions, AppProperties properties, ObjectMapper mapper) {
        this.sessions = sessions;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        String origin = request.getHeader("Origin");
        if (origin != null && properties.allowedOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }
        if ("OPTIONS".equals(request.getMethod())) {
            if (!originAllowed(origin)) { reject(response, 403, "ORIGIN_REJECTED", "Origin is not allowed", requestId); return; }
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PATCH,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type,Idempotency-Key");
            response.setStatus(204);
            return;
        }

        boolean stateChanging = switch (request.getMethod()) {
            case "POST", "PATCH", "PUT", "DELETE" -> true;
            default -> false;
        };
        if (stateChanging && !originAllowed(origin)) {
            reject(response, 403, "ORIGIN_REJECTED", "Origin is not allowed", requestId);
            return;
        }

        String path = request.getRequestURI();
        long requestLimit = pathIsRecordingChunk(request.getRequestURI()) ? 8L * 1024 * 1024 : 1_048_576;
        if (request.getContentLengthLong() > requestLimit) {
            reject(response, 413, "REQUEST_TOO_LARGE", "Request body exceeds the allowed size", requestId);
            return;
        }
        boolean login = ("/api/v1/session".equals(path) || "/api/v1/session/setup".equals(path))
                && "POST".equals(request.getMethod());
        if (login && !allowLogin(request.getRemoteAddr())) {
            reject(response, 429, "RATE_LIMITED", "Too many login attempts", requestId);
            return;
        }
        boolean publicPath = "/actuator/health".equals(path) || login
                || ("/api/v1/session/configuration".equals(path) && "GET".equals(request.getMethod()));
        if (!publicPath && !sessions.isValid(SessionController.cookieValue(request))) {
            reject(response, 401, "UNAUTHORIZED", "Authentication is required", requestId);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean pathIsRecordingChunk(String path) {
        return path.startsWith("/api/v1/meetings/") && path.endsWith("/recording-chunks");
    }

    private boolean originAllowed(String origin) {
        return origin != null && properties.allowedOrigins().contains(origin);
    }

    private boolean allowLogin(String address) {
        long minute = Instant.now().getEpochSecond() / 60;
        LoginWindow window = loginWindows.compute(address == null ? "unknown" : address,
                (key, old) -> old == null || old.minute != minute ? new LoginWindow(minute, 1) : old.incremented());
        return window.count <= LOGIN_ATTEMPTS_PER_MINUTE;
    }

    private void reject(HttpServletResponse response, int status, String code, String message, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, requestId));
    }

    private record LoginWindow(long minute, int count) {
        LoginWindow incremented() { return new LoginWindow(minute, count + 1); }
    }
}
