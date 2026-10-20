package dev.aimeeting.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import dev.aimeeting.api.config.AppProperties;

@Service
public class SessionService {
    public static final String COOKIE_NAME = "meeting_session";
    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private final byte[] expectedToken;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private volatile Consumer<String> invalidationListener = ignored -> {};

    public SessionService(AppProperties properties) {
        String token = properties.ownerToken();
        if (token == null || token.length() < 32) {
            throw new IllegalStateException("APP_OWNER_TOKEN must contain at least 32 characters");
        }
        expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    public String login(String suppliedToken) {
        byte[] supplied = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, supplied)) {
            return null;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(id, Instant.now().plus(SESSION_LIFETIME));
        return id;
    }

    public boolean isValid(String sessionId) {
        if (sessionId == null) return false;
        Instant expiry = sessions.get(sessionId);
        if (expiry == null) return false;
        if (expiry.isBefore(Instant.now())) {
            sessions.remove(sessionId, expiry);
            invalidationListener.accept(sessionId);
            return false;
        }
        return true;
    }

    public void logout(String sessionId) {
        if (sessionId != null && sessions.remove(sessionId) != null) invalidationListener.accept(sessionId);
    }

    public void onInvalidation(Consumer<String> listener) {
        this.invalidationListener = listener == null ? ignored -> {} : listener;
    }
}
