package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aimeeting.api.meeting.MeetingRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DeepgramStartupRecoveryTest {
    @Test
    void probeDiagnosticsRetainSqlStateWithoutLeakingDriverMessage() throws Exception {
        Fixture fixture = new Fixture();
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DeepgramStartupRecovery.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            recovery.start();
            fixture.verifyFailure.set(new SQLException("credential-shaped-driver-detail", "08006"));
            assertThatThrownBy(recovery::verifyOwnership).hasMessageContaining("restart required")
                    .hasNoCause();
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("java.sql.SQLException", "08006")
                        .doesNotContain("credential-shaped-driver-detail");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            recovery.destroy();
        }
    }

    @Test
    void startsRecoversAndVerifiesTheSameBackendLock() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.meetings.recoverOrphanedDeepgramSessions()).thenReturn(3);
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));

        assertThat(recovery.start()).isEqualTo(3);
        recovery.verifyOwnership();

        assertThat(recovery.isOwned()).isTrue();
        verify(fixture.verifyStatement, atLeastOnce()).setQueryTimeout(2);
        verify(fixture.connection, atLeastOnce())
                .setNetworkTimeout(org.mockito.ArgumentMatchers.any(), eq(3_000));
        recovery.destroy();
    }

    @Test
    void missingLockOrChangedBackendPidBecomesStickyLost() throws Exception {
        Fixture missing = new Fixture();
        DeepgramStartupRecovery missingRecovery = missing.recovery(Duration.ofDays(1));
        missingRecovery.start();
        missing.lockPresent.set(false);

        assertThatThrownBy(missingRecovery::verifyOwnership).isInstanceOf(IllegalStateException.class);
        assertThat(missingRecovery.isOwned()).isFalse();
        missing.lockPresent.set(true);
        assertThatThrownBy(missingRecovery::verifyOwnership).isInstanceOf(IllegalStateException.class);

        Fixture changedPid = new Fixture();
        DeepgramStartupRecovery changedRecovery = changedPid.recovery(Duration.ofDays(1));
        changedRecovery.start();
        changedPid.currentPid.set(202);
        assertThatThrownBy(changedRecovery::verifyOwnership).isInstanceOf(IllegalStateException.class);
        assertThat(changedRecovery.isOwned()).isFalse();
    }

    @Test
    void verificationSqlFailureMarksLostAndNotifiesAllListenersDespiteFailure() throws Exception {
        Fixture fixture = new Fixture();
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));
        recovery.start();
        AtomicInteger notified = new AtomicInteger();
        recovery.onOwnershipLost(() -> { throw new IllegalStateException("listener failure"); });
        recovery.onOwnershipLost(notified::incrementAndGet);
        fixture.verifyFailure.set(new SQLException("database detail"));

        assertThatThrownBy(recovery::verifyOwnership).isInstanceOf(IllegalStateException.class);

        assertThat(recovery.isOwned()).isFalse();
        assertThat(notified).hasValue(1);
    }

    @Test
    void verificationRuntimeFailureAlsoFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));
        recovery.start();
        fixture.verifyRuntimeFailure.set(new IllegalStateException("driver runtime failure"));

        assertThatThrownBy(recovery::verifyOwnership).isInstanceOf(IllegalStateException.class);

        assertThat(recovery.isOwned()).isFalse();
        verify(fixture.connection).abort(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unregisterSuppressesCallbackAndLateRegistrationRunsExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));
        recovery.start();
        AtomicInteger removed = new AtomicInteger();
        Runnable unregister = recovery.onOwnershipLost(removed::incrementAndGet);
        unregister.run();
        fixture.lockPresent.set(false);
        assertThatThrownBy(recovery::verifyOwnership).isInstanceOf(IllegalStateException.class);
        AtomicInteger late = new AtomicInteger();

        Runnable lateUnregister = recovery.onOwnershipLost(late::incrementAndGet);
        lateUnregister.run();

        assertThat(removed).hasValue(0);
        assertThat(late).hasValue(1);
    }

    @Test
    void registrationRacingWithLossStillNotifiesOnce() throws Exception {
        Fixture fixture = new Fixture();
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));
        recovery.start();
        fixture.lockPresent.set(false);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger notified = new AtomicInteger();
        CompletableFuture<Void> register = CompletableFuture.runAsync(() -> {
            await(start);
            recovery.onOwnershipLost(notified::incrementAndGet);
        });
        CompletableFuture<Void> lose = CompletableFuture.runAsync(() -> {
            await(start);
            try {
                recovery.verifyOwnership();
            } catch (IllegalStateException expected) {
                // Expected ownership transition.
            }
        });

        start.countDown();
        CompletableFuture.allOf(register, lose).get(1, TimeUnit.SECONDS);

        assertThat(notified).hasValue(1);
    }

    @Test
    void failedRecoveryReleasesAndClosesTheAcquiredSession() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.meetings.recoverOrphanedDeepgramSessions())
                .thenThrow(new IllegalStateException("recovery failed"));
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));

        assertThatThrownBy(recovery::start).isInstanceOf(IllegalStateException.class);

        assertThat(recovery.isOwned()).isFalse();
        verify(fixture.connection).abort(org.mockito.ArgumentMatchers.any());
        verify(fixture.connection).close();
    }

    @Test
    void monitorDetectsLossWithoutAnExplicitProbe() throws Exception {
        Fixture fixture = new Fixture();
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofMillis(10));
        AtomicInteger notified = new AtomicInteger();
        recovery.start();
        recovery.onOwnershipLost(notified::incrementAndGet);
        fixture.lockPresent.set(false);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (notified.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);

        assertThat(notified).hasValue(1);
        assertThat(recovery.isOwned()).isFalse();
        recovery.destroy();
    }

    @Test
    void destroyClosesTheSessionAndNotifiesRegisteredAndLaterListenersOnce() throws Exception {
        Fixture fixture = new Fixture();
        DeepgramStartupRecovery recovery = fixture.recovery(Duration.ofDays(1));
        recovery.start();
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();
        recovery.onOwnershipLost(registered::incrementAndGet);

        recovery.destroy();
        recovery.destroy();
        recovery.onOwnershipLost(late::incrementAndGet);

        assertThat(recovery.isOwned()).isFalse();
        assertThat(registered).hasValue(1);
        assertThat(late).hasValue(1);
        verify(fixture.connection).close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class Fixture {
        private final DataSource dataSource = mock(DataSource.class);
        private final MeetingRepository meetings = mock(MeetingRepository.class);
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement acquireStatement = mock(PreparedStatement.class);
        private final PreparedStatement verifyStatement = mock(PreparedStatement.class);
        private final PreparedStatement unlockStatement = mock(PreparedStatement.class);
        private final AtomicBoolean lockPresent = new AtomicBoolean(true);
        private final AtomicLong currentPid = new AtomicLong(101);
        private final AtomicReference<SQLException> verifyFailure = new AtomicReference<>();
        private final AtomicReference<RuntimeException> verifyRuntimeFailure = new AtomicReference<>();

        private Fixture() throws Exception {
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("pg_try_advisory_lock")) return acquireStatement;
                if (sql.contains("pg_advisory_unlock")) return unlockStatement;
                return verifyStatement;
            });
            when(acquireStatement.executeQuery()).thenAnswer(ignored -> result(true, 101));
            when(verifyStatement.executeQuery()).thenAnswer(ignored -> {
                SQLException failure = verifyFailure.get();
                if (failure != null) throw failure;
                RuntimeException runtimeFailure = verifyRuntimeFailure.get();
                if (runtimeFailure != null) throw runtimeFailure;
                return result(lockPresent.get(), currentPid.get());
            });
            when(unlockStatement.executeQuery()).thenAnswer(ignored -> result(true, 101));
        }

        private DeepgramStartupRecovery recovery(Duration interval) {
            return new DeepgramStartupRecovery(dataSource, meetings, interval);
        }

        private ResultSet result(boolean owned, long pid) throws Exception {
            ResultSet result = mock(ResultSet.class);
            when(result.next()).thenReturn(true);
            when(result.getBoolean(1)).thenReturn(owned);
            when(result.getLong(2)).thenReturn(pid);
            when(result.getInt(2)).thenReturn(Math.toIntExact(pid));
            return result;
        }
    }
}
