package dev.aimeeting.api.auth;

import dev.aimeeting.api.meeting.MeetingRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    @PostMapping
    public Map<String, Boolean> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                      HttpServletResponse response) {
        String sessionId = sessions.login(request.token());
        if (sessionId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid owner token");
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(sessionId, Duration.ofHours(12), httpRequest.isSecure()).toString());
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

    private ResponseCookie cookie(String value, Duration age, boolean secure) {
        return ResponseCookie.from(SessionService.COOKIE_NAME, value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(age).build();
    }

    public record LoginRequest(@NotBlank @Size(max = 512) String token) {}
}
