package dev.aimeeting.api.provider.deepgram;

import dev.aimeeting.api.meeting.MeetingRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optional pre-traffic recovery gate for the future production Deepgram integration.
 * The PostgreSQL session advisory lock proves that no other application instance owns recovery.
 */
@Component
@ConditionalOnProperty(name = "app.deepgram.startup-recovery-enabled", havingValue = "true")
public final class DeepgramStartupRecovery implements SmartInitializingSingleton, DisposableBean {
    private static final long LOCK_KEY = 0x41494D454554494EL; // "AIMEETIN", stable across releases

    private final DataSource dataSource;
    private final MeetingRepository meetings;
    private Connection lockConnection;

    public DeepgramStartupRecovery(DataSource dataSource, MeetingRepository meetings) {
        this.dataSource = dataSource;
        this.meetings = meetings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        start();
    }

    public synchronized int start() {
        if (lockConnection != null) throw new IllegalStateException("Deepgram startup recovery already owns the lock");
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            if (!"PostgreSQL".equals(candidate.getMetaData().getDatabaseProductName())) {
                throw new IllegalStateException("Deepgram startup recovery requires PostgreSQL");
            }
            boolean acquired;
            try (var statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    acquired = result.next() && result.getBoolean(1);
                }
            }
            if (!acquired) throw new IllegalStateException("Another application instance owns Deepgram recovery");
            lockConnection = candidate;
            candidate = null;
            return meetings.recoverOrphanedDeepgramSessions();
        } catch (SQLException failure) {
            close(candidate);
            throw new IllegalStateException("Deepgram startup recovery lock failed", failure);
        } catch (RuntimeException failure) {
            close(candidate);
            if (lockConnection != null) {
                release(lockConnection);
                lockConnection = null;
            }
            throw failure;
        }
    }

    @Override
    public synchronized void destroy() {
        release(lockConnection);
        lockConnection = null;
    }

    private static void release(Connection connection) {
        if (connection == null) return;
        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.executeQuery();
        } catch (SQLException ignored) {
            // A broken physical connection releases its session lock when PostgreSQL closes it.
        } finally {
            close(connection);
        }
    }

    private static void close(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing the physical session is the advisory-lock release; shutdown cannot recover from a DB outage.
        }
    }
}
