package dev.aimeeting.api.meeting;

import dev.aimeeting.api.storage.RecordingStorage;
import dev.aimeeting.api.storage.RecordingStorage.Quarantine;
import dev.aimeeting.api.web.ApiException;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MeetingDeletionService {
    private static final Logger logger = LoggerFactory.getLogger(MeetingDeletionService.class);
    private final MeetingRepository meetings;
    private final RecordingStorage storage;
    private final TransactionTemplate transactions;

    public MeetingDeletionService(MeetingRepository meetings, RecordingStorage storage,
                                  TransactionTemplate transactions) {
        this.meetings = meetings;
        this.storage = storage;
        this.transactions = transactions;
    }

    public DeletionResult delete(UUID meetingId, int expectedRevision) {
        if (expectedRevision < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVISION", "Meeting revision must be nonnegative");
        }
        Quarantine[] quarantined = new Quarantine[1];
        try {
            transactions.executeWithoutResult(status -> {
                UUID activeMeetingId = meetings.lockActiveMeeting();
                Meeting meeting = meetings.lock(meetingId);
                if (meeting.revision() != expectedRevision) {
                    throw new ApiException(HttpStatus.CONFLICT, "STALE_REVISION", "Meeting revision is stale");
                }
                if ("STARTED".equals(meeting.status()) || meeting.id().equals(activeMeetingId)) {
                    throw new ApiException(HttpStatus.CONFLICT, "MEETING_ACTIVE", "Active meetings cannot be deleted");
                }
                try {
                    quarantined[0] = storage.quarantine(meetingId);
                } catch (IOException exception) {
                    throw new ApiException(HttpStatus.CONFLICT, "RECORDING_STORAGE_UNSAFE",
                            "Recording files could not be safely prepared for deletion");
                }
                meetings.deleteLocked(meetingId);
            });
        } catch (RuntimeException failure) {
            restoreAfterFailure(quarantined[0], failure);
            throw failure;
        }

        boolean cleanupPending = false;
        if (quarantined[0] != null) {
            try {
                storage.purge(quarantined[0]);
            } catch (IOException exception) {
                cleanupPending = true;
                logger.warn("Meeting recording cleanup remains pending for {}", meetingId);
            }
        }
        return new DeletionResult(true, cleanupPending);
    }

    private void restoreAfterFailure(Quarantine quarantine, RuntimeException original) {
        if (quarantine == null) return;
        try {
            transactions.executeWithoutResult(status -> {
                meetings.lock(quarantine.meetingId());
                try {
                    storage.restore(quarantine);
                } catch (IOException restoreFailure) {
                    throw new QuarantineRestoreException(restoreFailure);
                }
            });
        } catch (RuntimeException restoreFailure) {
            original.addSuppressed(restoreFailure);
            logger.error("Meeting recording quarantine could not be restored after database rollback");
        }
    }

    private static final class QuarantineRestoreException extends RuntimeException {
        private QuarantineRestoreException(IOException cause) { super(cause); }
    }

    public record DeletionResult(boolean deleted, boolean cleanupPending) {}
}
