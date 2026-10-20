package dev.aimeeting.api.meeting;

import dev.aimeeting.api.storage.RecordingStorage;
import dev.aimeeting.api.storage.RecordingStorage.Quarantine;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RecordingDeletionReconciler {
    private static final Logger logger = LoggerFactory.getLogger(RecordingDeletionReconciler.class);
    private final RecordingStorage storage;
    private final JdbcTemplate jdbc;
    private final MeetingRepository meetings;
    private final TransactionTemplate transactions;

    public RecordingDeletionReconciler(RecordingStorage storage, JdbcTemplate jdbc, MeetingRepository meetings,
                                       TransactionTemplate transactions) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.meetings = meetings;
        this.transactions = transactions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        try {
            for (Quarantine quarantine : storage.quarantines()) {
                Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM meetings WHERE id=?", Integer.class,
                        quarantine.meetingId());
                if (rows != null && rows > 0) {
                    restoreReferenced(quarantine);
                } else {
                    try {
                        storage.purge(quarantine);
                    } catch (IOException cleanupFailure) {
                        logger.warn("A validated recording quarantine remains pending cleanup");
                    }
                }
            }
        } catch (IOException unsafeStorage) {
            throw new IllegalStateException("Recording deletion reconciliation failed closed", unsafeStorage);
        }
    }

    private void restoreReferenced(Quarantine quarantine) throws IOException {
        try {
            transactions.executeWithoutResult(status -> {
                meetings.lock(quarantine.meetingId());
                try {
                    storage.restore(quarantine);
                } catch (IOException exception) {
                    throw new ReconciliationIOException(exception);
                }
            });
        } catch (ReconciliationIOException exception) {
            throw (IOException) exception.getCause();
        }
    }

    private static final class ReconciliationIOException extends RuntimeException {
        private ReconciliationIOException(IOException cause) { super(cause); }
    }
}
