package dev.aimeeting.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aimeeting.api.config.AppProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:session-notifications;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class SessionLifecycleNotificationTest {
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void providerCleanupRunsAfterOutermostLifecycleGuardIsReleased() throws Exception {
        SessionService auth = new SessionService(properties, jdbc);
        String id = auth.login(properties.ownerToken());
        AtomicBoolean acquired = installListener(auth);
        auth.withLifecycleGuard(() -> auth.logout(id));
        assertThat(acquired).isTrue();
        assertThat(auth.isValid(id)).isFalse();
    }

    @Test
    void committedRevocationNotifiesAfterTransactionGuardRelease() {
        SessionService auth = new SessionService(properties, jdbc);
        String id = auth.login(properties.ownerToken());
        AtomicBoolean acquired = installListener(auth);
        new TransactionTemplate(transactions).executeWithoutResult(status -> auth.logout(id));
        assertThat(acquired).isTrue();
        assertThat(auth.isValid(id)).isFalse();
    }

    @Test
    void rolledBackRevocationDoesNotNotifyOrLeaveTheGuardLocked() throws Exception {
        SessionService auth = new SessionService(properties, jdbc);
        String id = auth.login(properties.ownerToken());
        AtomicInteger notifications = new AtomicInteger();
        auth.onInvalidation(ignored -> notifications.incrementAndGet());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            auth.logout(id);
            status.setRollbackOnly();
        });
        assertThat(notifications).hasValue(0);
        assertThat(auth.isValid(id)).isTrue();
        assertThat(canAcquireGuard(auth)).isTrue();
    }

    private AtomicBoolean installListener(SessionService auth) {
        AtomicBoolean acquired = new AtomicBoolean();
        auth.onInvalidation(ignored -> {
            try { acquired.set(canAcquireGuard(auth)); }
            catch (Exception failure) { acquired.set(false); }
        });
        return acquired;
    }

    private boolean canAcquireGuard(SessionService auth) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try { auth.withLifecycleGuard(() -> {}); return true; }
            catch (Exception failure) { return false; }
        }).get(2, TimeUnit.SECONDS);
    }
}
