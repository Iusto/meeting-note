package dev.aimeeting.api.auth;

import dev.aimeeting.api.meeting.MeetingRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {
    private final SessionService sessions;
    private final MeetingRepository meetings;

    public SessionController(SessionService sessions, MeetingRepository meetings) {
        this.sessions = sessions;
        this.meetings = meetings;
    }

    @GetMapping("/configuration")
    public Map<String, Boolean> configuration() { return Map.of("setupRequired", sessions.setupRequired()); }

    @PostMapping("/setup")
    public Map<String, Boolean> setup(@Valid @RequestBody SetupRequest request, HttpServletRequest httpRequest,
                                      HttpServletResponse response) {
        SessionService.AuthenticatedSession session = sessions.setup(
                request.token(), request.username(), request.password(), request.rememberMe());
        if (session == null) throw unauthorized();
        setCookie(response, httpRequest, session);
        return Map.of("authenticated", true);
    }

    @PostMapping
    public Map<String, Boolean> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                      HttpServletResponse response) {
        String previous = cookieValue(httpRequest);
        SessionService.AuthenticatedSession session = request.token() != null
                ? sessions.loginWithToken(request.token(), previous)
                : sessions.login(request.username(), request.password(), request.rememberMe(), previous);
        if (session == null) throw unauthorized();
        setCookie(response, httpRequest, session);
        return Map.of("authenticated", true);
    }

    @GetMapping
    public Map<String, Boolean> status() { return Map.of("authenticated", true); }

    @DeleteMapping
    public Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        meetings.stopActiveLocalMeeting();
        sessions.logout(cookieValue(request));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request.isSecure()).toString());
        return Map.of("authenticated", false);
    }

    static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (SessionService.COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private void setCookie(HttpServletResponse response, HttpServletRequest request,
                           SessionService.AuthenticatedSession session) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(session.id(), session.lifetime(), request.isSecure()).toString());
    }

    private ResponseCookie cookie(String value, Duration age, boolean secure) {
        return ResponseCookie.from(SessionService.COOKIE_NAME, value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(age).build();
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    public record LoginRequest(@Size(max = 512) String token, @Size(max = 64) String username,
                               @Size(max = 128) String password, boolean rememberMe) {}
    public record SetupRequest(@Size(max = 512) String token, @Size(max = 64) String username,
                               @Size(max = 128) String password, boolean rememberMe) {}
}
