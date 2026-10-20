package dev.aimeeting.api.live;

import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.config.AppProperties;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class LiveHandshakeInterceptor implements HandshakeInterceptor {
    private final SessionService sessions;
    private final AppProperties properties;

    public LiveHandshakeInterceptor(SessionService sessions, AppProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || !properties.allowedOrigins().contains(origin)) return false;
        for (String raw : request.getHeaders().getOrDefault(HttpHeaders.COOKIE, java.util.List.of())) {
            for (String pair : raw.split(";")) {
                int separator = pair.indexOf('=');
                if (separator <= 0) continue;
                String name = pair.substring(0, separator).trim();
                String value = pair.substring(separator + 1).trim();
                if (SessionService.COOKIE_NAME.equals(name) && sessions.isValid(value)) {
                    attributes.put("authSessionId", value);
                    return true;
                }
            }
        }
        return false;
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler handler, Exception exception) {}
}
