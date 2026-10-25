package dev.aimeeting.api.auth;

import dev.aimeeting.api.config.AppProperties;
import dev.aimeeting.api.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    public static final String COOKIE_NAME = "meeting_session";
    static final Duration STANDARD_LIFETIME = Duration.ofHours(12);
    static final Duration REMEMBERED_LIFETIME = Duration.ofDays(30);
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int HASH_BITS = 256;
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,64}");
    private static final byte[] DUMMY_SALT = "ai-meeting-unknown-owner-dummy-salt".getBytes(StandardCharsets.UTF_8);

    private final byte[] expectedToken;
    private final JdbcTemplate jdbc;
    private final SecureRandom random;
    private final byte[] dummyHash;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile Consumer<String> invalidationListener = ignored -> {};
    private volatile Runnable allInvalidationListener = () -> {};

    @Autowired
    public SessionService(AppProperties properties, JdbcTemplate jdbc) {
        this(properties, jdbc, new SecureRandom());
    }

    SessionService(AppProperties properties, JdbcTemplate jdbc, SecureRandom random) {
        String token = properties.ownerToken();
        if (token == null || token.length() < 32) {
            throw new IllegalStateException("APP_OWNER_TOKEN must contain at least 32 characters");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.jdbc = jdbc;
        this.random = random;
        this.dummyHash = passwordHash("invalid-password", DUMMY_SALT);
    }

    public boolean setupRequired() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM owner_account WHERE id=1", Integer.class) == 0;
    }

    @Transactional
    public AuthenticatedSession setup(String suppliedToken, String username, String password, boolean rememberMe) {
        if (!tokenMatches(suppliedToken)) return null;
        try (LifecycleLease ignored = acquireMutationGuard()) {
            lockAuthenticationSetup();
            if (!setupRequired()) {
                throw new ApiException(HttpStatus.CONFLICT, "OWNER_ALREADY_CONFIGURED", "Owner account is already configured");
            }
            String normalized = normalizeUsername(username);
            validatePassword(password);
            byte[] salt = randomBytes(32);
            byte[] hash = passwordHash(password, salt);
            try {
                jdbc.update("INSERT INTO owner_account(id,username,password_salt,password_hash) VALUES (1,?,?,?)",
                        normalized, encode(salt), encode(hash));
            } catch (DataIntegrityViolationException duplicate) {
                throw new ApiException(HttpStatus.CONFLICT, "OWNER_ALREADY_CONFIGURED", "Owner account is already configured");
            }
            jdbc.update("DELETE FROM auth_sessions");
            afterCommit(() -> notifySafely("owner setup", allInvalidationListener));
            return createSession(rememberMe);
        }
    }

    @Transactional
    public AuthenticatedSession login(String username, String password, boolean rememberMe, String previousSessionId) {
        String normalized = normalizeForLookup(username);
        List<Credentials> matches = normalized == null ? List.of() : jdbc.query(
                "SELECT password_salt,password_hash FROM owner_account WHERE id=1 AND username=?",
                (rs, row) -> new Credentials(decode(rs.getString(1)), decode(rs.getString(2))), normalized);
        Credentials credentials = matches.isEmpty() ? new Credentials(DUMMY_SALT, dummyHash) : matches.getFirst();
        byte[] actual = passwordHash(password == null ? "" : password, credentials.salt());
        if (matches.isEmpty() || !MessageDigest.isEqual(credentials.hash(), actual)) return null;
        try (LifecycleLease ignored = acquireMutationGuard()) {
            revoke(previousSessionId);
            return createSession(rememberMe);
        }
    }

    @Transactional
    public AuthenticatedSession loginWithToken(String suppliedToken, String previousSessionId) {
        if (!tokenMatches(suppliedToken)) return null;
        try (LifecycleLease ignored = acquireMutationGuard()) {
            lockAuthenticationSetup();
            if (!setupRequired()) return null;
            revoke(previousSessionId);
            return createSession(false);
        }
    }

    /** Retains the pre-setup test/application seam while legacy token login remains supported. */
    public String login(String suppliedToken) {
        AuthenticatedSession session = loginWithToken(suppliedToken, null);
        return session == null ? null : session.id();
    }

    public boolean isValid(String sessionId) {
        if (!validSessionIdFormat(sessionId)) return false;
        String hash = sessionHash(sessionId);
        List<OffsetDateTime> rows = jdbc.query("SELECT expires_at FROM auth_sessions WHERE id_hash=?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class), hash);
        if (rows.isEmpty()) return false;
        if (!rows.getFirst().isAfter(now())) {
            revoke(sessionId);
            cleanupExpired();
            return false;
        }
        return true;
    }

    public void logout(String sessionId) {
        revoke(sessionId);
    }

    public void onInvalidation(Consumer<String> listener) {
        this.invalidationListener = listener == null ? ignored -> {} : listener;
    }

    public void onAllInvalidation(Runnable listener) {
        this.allInvalidationListener = listener == null ? () -> {} : listener;
    }

    public void withLifecycleGuard(LifecycleAction action) throws Exception {
        lifecycleLock.lock();
        try {
            action.run();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private AuthenticatedSession createSession(boolean rememberMe) {
        cleanupExpired();
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        Duration lifetime = rememberMe ? REMEMBERED_LIFETIME : STANDARD_LIFETIME;
        jdbc.update("INSERT INTO auth_sessions(id_hash,expires_at) VALUES (?,?)",
                sessionHash(id), now().plus(lifetime));
        return new AuthenticatedSession(id, lifetime);
    }

    private boolean revoke(String sessionId) {
        try (LifecycleLease ignored = acquireMutationGuard()) {
            boolean revoked = validSessionIdFormat(sessionId)
                    && jdbc.update("DELETE FROM auth_sessions WHERE id_hash=?", sessionHash(sessionId)) != 0;
            if (revoked) afterCommit(() -> notifySafely("session revoke", () -> invalidationListener.accept(sessionId)));
            return revoked;
        }
    }

    private LifecycleLease acquireMutationGuard() {
        lifecycleLock.lock();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return lifecycleLock::unlock;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { lifecycleLock.unlock(); }
        });
        return () -> {};
    }

    private void notifySafely(String operation, Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException failure) {
            log.warn("Session invalidation cleanup failed: operation={}, type={}",
                    operation, failure.getClass().getSimpleName());
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private void lockAuthenticationSetup() {
        jdbc.queryForObject("SELECT id FROM runtime_state WHERE id=1 FOR UPDATE", Integer.class);
    }

    private void cleanupExpired() {
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at<=?", now());
    }

    private boolean tokenMatches(String suppliedToken) {
        byte[] supplied = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, supplied);
    }

    private static String normalizeUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Username must contain 3 to 64 allowed ASCII characters");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private static String normalizeForLookup(String username) {
        return username != null && USERNAME.matcher(username).matches() ? username.toLowerCase(Locale.ROOT) : null;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Password must contain 12 to 128 characters");
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static boolean validSessionIdFormat(String id) {
        return id != null && SESSION_ID.matcher(id).matches();
    }

    private static String sessionHash(String id) {
        try {
            return encode(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] passwordHash(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", unavailable);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static byte[] decode(String value) { return Base64.getDecoder().decode(value); }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    public record AuthenticatedSession(String id, Duration lifetime) {}
    @FunctionalInterface public interface LifecycleAction { void run() throws Exception; }
    @FunctionalInterface private interface LifecycleLease extends AutoCloseable { @Override void close(); }
    private record Credentials(byte[] salt, byte[] hash) {}
}
