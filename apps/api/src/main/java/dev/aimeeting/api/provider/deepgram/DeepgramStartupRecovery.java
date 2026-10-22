package dev.aimeeting.api.provider.deepgram;

import dev.aimeeting.api.meeting.MeetingRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional pre-traffic recovery gate for the future production Deepgram integration.
 * Loss of the dedicated PostgreSQL session permanently disables guarded Deepgram work in this instance.
 */
@Component
@ConditionalOnProperty(name = "app.deepgram.startup-recovery-enabled", havingValue = "true")
public final class DeepgramStartupRecovery implements SmartInitializingSingleton, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DeepgramStartupRecovery.class);
    private static final long LOCK_KEY = 0x41494D454554494EL; // "AIMEETIN", stable across releases
    private static final String OWNERSHIP_QUERY = """
            SELECT EXISTS (
                SELECT 1 FROM pg_locks
                 WHERE locktype='advisory' AND pid=pg_backend_pid() AND granted
                   AND mode='ExclusiveLock' AND objsubid=1
                   AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
                   AND classid=((?::bigint >> 32) & 4294967295)::oid
                   AND objid=(?::bigint & 4294967295)::oid
            ), pg_backend_pid()
            """;
    private enum State { NEW, STARTING, OWNED, LOST, CLOSED }

    private final DataSource dataSource;
    private final MeetingRepository meetings;
    private final long monitorIntervalMs;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "deepgram-lock-monitor");
        thread.setDaemon(true);
        return thread;
    });
    // Separate from the monitor: a blocked probe must not prevent JDBC abort/timeout work.
    private final ExecutorService networkExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Runnable> lossListeners = new LinkedHashSet<>();
    private volatile State state = State.NEW;
    private Connection lockConnection;
    private int backendPid;

    @Autowired
    public DeepgramStartupRecovery(DataSource dataSource, MeetingRepository meetings) {
        this(dataSource, meetings, Duration.ofSeconds(1));
    }

    DeepgramStartupRecovery(DataSource dataSource, MeetingRepository meetings, Duration monitorInterval) {
        this.dataSource = dataSource;
        this.meetings = meetings;
        this.monitorIntervalMs = Objects.requireNonNull(monitorInterval).toMillis();
        if (monitorIntervalMs < 1) throw new IllegalArgumentException("Monitor interval must be positive");
    }

    @Override
    public void afterSingletonsInstantiated() {
        start();
    }

    public synchronized int start() {
        if (state != State.NEW) throw new IllegalStateException("Deepgram startup recovery cannot be restarted");
        state = State.STARTING;
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            candidate.setNetworkTimeout(networkExecutor, 3_000);
            if (!"PostgreSQL".equals(candidate.getMetaData().getDatabaseProductName())) {
                throw new IllegalStateException("Deepgram startup recovery requires PostgreSQL");
            }
            boolean acquired;
            try (var statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?), pg_backend_pid()")) {
                statement.setQueryTimeout(2);
                statement.setLong(1, LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    acquired = result.next() && result.getBoolean(1);
                    if (acquired) backendPid = result.getInt(2);
                }
            }
            if (!acquired) throw new IllegalStateException("Another application instance owns Deepgram recovery");
            lockConnection = candidate;
            candidate = null;
            int recovered = meetings.recoverOrphanedDeepgramSessions();
            if (!ownsLock()) throw new IllegalStateException("Deepgram startup recovery lost ownership");
            state = State.OWNED;
            monitor.scheduleWithFixedDelay(this::monitorOwnership, monitorIntervalMs, monitorIntervalMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return recovered;
        } catch (SQLException failure) {
            failedStart(candidate);
            throw new IllegalStateException("Deepgram startup recovery lock failed", failure);
        } catch (RuntimeException failure) {
            failedStart(candidate);
            throw failure;
        }
    }

    /** Synchronous admission check; never put this JDBC probe on the per-audio-frame path. */
    public void verifyOwnership() {
        Connection lost;
        List<Runnable> listeners;
        Throwable probeFailure = null;
        synchronized (this) {
            if (state != State.OWNED) throw unavailable();
            try {
                if (ownsLock()) return;
            } catch (SQLException | RuntimeException failure) {
                probeFailure = failure;
                // A failed probe is not permission to dispatch or to automatically reacquire the lock.
            }
            state = State.LOST;
            lost = lockConnection;
            lockConnection = null;
            listeners = drainListeners();
        }
        String sqlState = probeFailure instanceof SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().matches("[A-Z0-9]{5}") ? sql.getSQLState() : "unknown";
        // Driver messages/stack traces may include connection credentials. Retain safe diagnostic fields only.
        log.warn("Deepgram lock ownership lost; transcription disabled until application restart; cause={}, sqlState={}",
                probeFailure == null ? "lock_missing_or_backend_changed" : probeFailure.getClass().getName(), sqlState);
        monitor.shutdown();
        notifyLoss(listeners);
        abort(lost);
        networkExecutor.shutdown();
        throw unavailable();
    }

    public boolean isOwned() { return state == State.OWNED; }

    /** Returns an unregister action. A registration that loses the race is notified immediately, once. */
    public Runnable onOwnershipLost(Runnable listener) {
        Objects.requireNonNull(listener);
        Runnable registration = () -> listener.run();
        synchronized (this) {
            if (state == State.OWNED) {
                lossListeners.add(registration);
                return () -> { synchronized (this) { lossListeners.remove(registration); } };
            }
        }
        notifyLoss(List.of(registration));
        return () -> {};
    }

    @Override
    public void destroy() {
        Connection owned;
        List<Runnable> listeners;
        synchronized (this) {
            state = State.CLOSED;
            owned = lockConnection;
            lockConnection = null;
            listeners = drainListeners();
        }
        monitor.shutdown();
        notifyLoss(listeners);
        release(owned);
        networkExecutor.shutdown();
    }

    private boolean ownsLock() throws SQLException {
        try (var statement = lockConnection.prepareStatement(OWNERSHIP_QUERY)) {
            statement.setQueryTimeout(2);
            statement.setLong(1, LOCK_KEY);
            statement.setLong(2, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1) && result.getInt(2) == backendPid;
            }
        }
    }

    private void monitorOwnership() {
        try {
            verifyOwnership();
        } catch (IllegalStateException ignored) {
            // Sticky LOST/CLOSED state has already notified every registered Deepgram session.
        }
    }

    private List<Runnable> drainListeners() {
        List<Runnable> listeners = new ArrayList<>(lossListeners);
        lossListeners.clear();
        return listeners;
    }

    private static void notifyLoss(List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // One failed provider closer cannot keep another provider session active.
            }
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Deepgram recovery ownership is unavailable; restart required");
    }

    private void failedStart(Connection candidate) {
        state = State.LOST;
        monitor.shutdown();
        abort(candidate);
        abort(lockConnection);
        lockConnection = null;
        networkExecutor.shutdown();
    }

    private void release(Connection connection) {
        if (connection == null) return;
        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setQueryTimeout(2);
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) abort(connection);
            }
        } catch (SQLException ignored) {
            abort(connection);
        } finally {
            close(connection);
        }
    }

    private void abort(Connection connection) {
        if (connection == null) return;
        try {
            // Logical pooled close alone may leave the PostgreSQL session advisory lock behind.
            // Abort does not depend on an executor that destroy() may already have shut down.
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException ignored) {
            // The network deadline also closes the physical connection on an unreachable database.
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
