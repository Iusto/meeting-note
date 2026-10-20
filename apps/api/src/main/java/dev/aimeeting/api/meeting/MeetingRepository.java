package dev.aimeeting.api.meeting;

import dev.aimeeting.api.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MeetingRepository {
    private static final int MAX_FINAL_BATCH_SEGMENTS = 10_000;
    private final JdbcTemplate jdbc;

    public MeetingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Meeting> list() {
        return jdbc.query("SELECT id,title,status,input_source,topic_id,started_at,ended_at,revision FROM meetings ORDER BY created_at DESC", this::meeting);
    }

    public List<Meeting> search(String query) {
        String escaped = query.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        String pattern = "%" + escaped + "%";
        return jdbc.query("""
                SELECT m.id,m.title,m.status,m.input_source,m.topic_id,m.started_at,m.ended_at,m.revision
                  FROM meetings m
                 WHERE LOWER(m.title) LIKE ? ESCAPE '!'
                    OR EXISTS (SELECT 1 FROM transcript_segments ts
                                WHERE ts.meeting_id=m.id
                                  AND (LOWER(ts.original_text) LIKE ? ESCAPE '!'
                                    OR LOWER(ts.display_text) LIKE ? ESCAPE '!'))
                 ORDER BY m.created_at DESC
                """, this::meeting, pattern, pattern, pattern);
    }

    public Meeting find(UUID id) {
        List<Meeting> rows = jdbc.query("SELECT id,title,status,input_source,topic_id,started_at,ended_at,revision FROM meetings WHERE id=?", this::meeting, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "Meeting was not found");
        return rows.getFirst();
    }

    public Meeting lock(UUID id) {
        List<Meeting> rows = jdbc.query("SELECT id,title,status,input_source,topic_id,started_at,ended_at,revision FROM meetings WHERE id=? FOR UPDATE",
                this::meeting, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "Meeting was not found");
        return rows.getFirst();
    }

    public Meeting create(String title) {
        return create(title, InputSource.MOCK, null);
    }

    public Meeting create(String title, InputSource inputSource) {
        return create(title, inputSource, null);
    }

    public Meeting create(String title, InputSource inputSource, UUID topicId) {
        UUID id = UUID.randomUUID();
        if (topicId != null && jdbc.queryForObject("SELECT COUNT(*) FROM meeting_topics WHERE id=?", Integer.class, topicId) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOPIC_NOT_FOUND", "Meeting topic was not found");
        }
        jdbc.update("INSERT INTO meetings(id,title,status,input_source,topic_id) VALUES (?,?,?,?,?)", id, title.trim(), "CREATED", inputSource.name(), topicId);
        return find(id);
    }

    @Transactional
    public Meeting updateTitle(UUID id, String title, int expectedRevision) {
        Meeting current = lock(id);
        if (current.revision() != expectedRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_REVISION", "Meeting revision is stale");
        }
        jdbc.update("UPDATE meetings SET title=?,revision=revision+1 WHERE id=?", title.trim(), id);
        return find(id);
    }

    @Transactional
    public Meeting start(UUID id) {
        UUID activeMeetingId = lockActiveMeeting();
        Meeting current = find(id);
        if ("STOPPED".equals(current.status())) throw new ApiException(HttpStatus.CONFLICT, "MEETING_STOPPED", "Stopped meetings cannot be restarted");
        if (activeMeetingId != null && !activeMeetingId.equals(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_ALREADY_ACTIVE", "Another meeting is already active");
        }
        if (!"STARTED".equals(current.status())) {
            int changed = jdbc.update("UPDATE meetings SET status='STARTED',started_at=?,ended_at=NULL,revision=revision+1 WHERE id=? AND status='CREATED'",
                    OffsetDateTime.now(ZoneOffset.UTC), id);
            if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "INVALID_MEETING_STATE", "Meeting cannot be started");
            jdbc.update("UPDATE runtime_state SET active_meeting_id=? WHERE id=1", id);
        }
        return find(id);
    }

    @Transactional
    public Meeting stop(UUID id) {
        lockActiveMeeting();
        Meeting current = find(id);
        if (!"STOPPED".equals(current.status())) {
            int changed = jdbc.update("UPDATE meetings SET status='STOPPED',ended_at=?,revision=revision+1 WHERE id=? AND status IN ('CREATED','STARTED')",
                    OffsetDateTime.now(ZoneOffset.UTC), id);
            if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "INVALID_MEETING_STATE", "Meeting cannot be stopped");
        }
        jdbc.update("UPDATE runtime_state SET active_meeting_id=NULL WHERE id=1 AND active_meeting_id=?", id);
        return find(id);
    }

    @Transactional
    public void stopActiveLocalMeeting() {
        UUID activeMeetingId = lockActiveMeeting();
        if (activeMeetingId == null) return;
        Meeting active = find(activeMeetingId);
        if (!"LOCAL_RECORDING".equals(active.inputSource())) return;
        jdbc.update("UPDATE meetings SET status='STOPPED',ended_at=?,revision=revision+1 WHERE id=? AND status='STARTED'",
                OffsetDateTime.now(ZoneOffset.UTC), activeMeetingId);
        jdbc.update("UPDATE runtime_state SET active_meeting_id=NULL WHERE id=1 AND active_meeting_id=?", activeMeetingId);
    }

    public UUID lockActiveMeeting() {
        return jdbc.queryForObject("SELECT active_meeting_id FROM runtime_state WHERE id=1 FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    public void deleteLocked(UUID id) {
        if (jdbc.update("DELETE FROM meetings WHERE id=?", id) != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "Meeting was not found");
        }
    }

    public UUID createStreamSession(UUID meetingId) {
        return createStreamSession(meetingId, "FAKE", 0);
    }

    public UUID createStreamSession(UUID meetingId, String provider, long audioOffsetMs) {
        if (provider == null || provider.isBlank() || provider.length() > 40) {
            throw new IllegalArgumentException("provider must contain 1 to 40 characters");
        }
        if (audioOffsetMs < 0) {
            throw new IllegalArgumentException("audioOffsetMs must be nonnegative");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stream_sessions(id,meeting_id,provider,audio_offset_ms,started_at) VALUES (?,?,?,?,?)",
                id, meetingId, provider, audioOffsetMs, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    public void closeStreamSession(UUID id, String reason) {
        jdbc.update("UPDATE stream_sessions SET ended_at=?,close_reason=? WHERE id=? AND ended_at IS NULL",
                OffsetDateTime.now(ZoneOffset.UTC), reason, id);
    }

    @Transactional
    public TranscriptSegment insertFinal(UUID meetingId, String eventId, long sequence, long startMs, long endMs,
                                         String text, String speakerLabel) {
        String speakerStatus = speakerLabel == null || speakerLabel.isBlank() ? "UNKNOWN" : "TENTATIVE";
        return insertFinal(meetingId, eventId, sequence, startMs, endMs, text, speakerLabel, speakerStatus);
    }

    @Transactional
    public TranscriptSegment insertFinal(UUID meetingId, String eventId, long sequence, long startMs, long endMs,
                                         String text, String speakerLabel, String speakerStatus) {
        Meeting meeting = lock(meetingId);
        TranscriptSegment existing = findByEvent(meetingId, eventId);
        if (existing != null) return existing;
        if ("STOPPED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_CLOSED", "Stopped meeting transcripts cannot accept new final segments");
        }
        boolean hasSpeaker = speakerLabel != null && !speakerLabel.isBlank();
        if (speakerStatus == null || speakerStatus.isBlank()) {
            throw new IllegalArgumentException("speakerStatus must not be blank");
        }
        UUID speakerId = hasSpeaker ? ensureSpeaker(meetingId, speakerLabel, speakerStatus) : null;
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("INSERT INTO transcript_segments(id,meeting_id,provider_event_id,sequence_no,start_ms,end_ms,original_text,display_text,transcript_status,speaker_id,speaker_status) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                id, meetingId, eventId, sequence, startMs, endMs, text, text, "FINAL", speakerId,
                speakerStatus);
        return inserted == 1 ? findSegment(id) : findByEvent(meetingId, eventId);
    }

    @Transactional
    public List<TranscriptSegment> insertFinalBatchIfAbsent(UUID meetingId, List<FinalSegmentInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_FINAL_BATCH_SEGMENTS) {
            throw new IllegalArgumentException("final segment batch must contain 1 to 10000 items");
        }
        List<FinalSegmentInput> batch = List.copyOf(inputs);
        Meeting meeting = lock(meetingId);
        if (findByEvent(meetingId, batch.getFirst().eventId()) != null) return List.of();
        if ("STOPPED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_CLOSED", "Stopped meeting transcripts cannot accept new final segments");
        }

        Set<String> eventIds = new HashSet<>();
        for (FinalSegmentInput input : batch) {
            if (!eventIds.add(input.eventId())) {
                throw new IllegalArgumentException("final segment batch contains duplicate event IDs");
            }
            if (findByEvent(meetingId, input.eventId()) != null) {
                throw new IllegalStateException("final segment batch conflicts with an incomplete persisted event");
            }
        }

        List<TranscriptSegment> inserted = new ArrayList<>(batch.size());
        for (FinalSegmentInput input : batch) {
            UUID speakerId = input.speakerLabel() == null || input.speakerLabel().isBlank()
                    ? null : ensureSpeaker(meetingId, input.speakerLabel(), input.speakerStatus());
            UUID id = UUID.randomUUID();
            int changed = jdbc.update("INSERT INTO transcript_segments(id,meeting_id,provider_event_id,sequence_no,start_ms,end_ms,original_text,display_text,transcript_status,speaker_id,speaker_status) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    id, meetingId, input.eventId(), input.sequence(), input.startMs(), input.endMs(),
                    input.text(), input.text(), "FINAL", speakerId, input.speakerStatus());
            if (changed != 1) throw new IllegalStateException("final segment batch insert conflicted");
            inserted.add(findSegment(id));
        }
        return List.copyOf(inserted);
    }

    public record FinalSegmentInput(String eventId, long sequence, long startMs, long endMs,
                                    String text, String speakerLabel, String speakerStatus) {
        public FinalSegmentInput {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(speakerStatus, "speakerStatus");
            if (eventId.isBlank() || eventId.length() > 200) {
                throw new IllegalArgumentException("eventId must contain 1 to 200 characters");
            }
            if (speakerStatus.isBlank()) throw new IllegalArgumentException("speakerStatus must not be blank");
        }
    }

    @Transactional
    public TranscriptSegment updateSpeaker(UUID segmentId, String label, String status) {
        TranscriptSegment current = findSegment(segmentId);
        UUID meetingId = meetingIdForSegment(segmentId);
        UUID speakerId = ensureSpeaker(meetingId, label, status);
        jdbc.update("UPDATE transcript_segments SET speaker_id=?,speaker_status=? WHERE id=?", speakerId, status, segmentId);
        return findSegment(current.id());
    }

    @Transactional
    public TranscriptSegment correct(UUID meetingId, UUID segmentId, int expectedRevision, String displayText,
                                     String actor, String reason, boolean lockForUser) {
        lock(meetingId);
        TranscriptSegment current = findSegment(segmentId);
        if (!meetingId.equals(meetingIdForSegment(segmentId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SEGMENT_NOT_FOUND", "Segment was not found in this meeting");
        }
        if (!"USER".equals(actor) && current.userLocked()) {
            throw new ApiException(HttpStatus.CONFLICT, "SEGMENT_LOCKED", "User-locked text cannot be overwritten");
        }
        int changed = jdbc.update("UPDATE transcript_segments SET display_text=?,transcript_status='CORRECTED',revision=revision+1,user_locked=? WHERE id=? AND revision=?",
                displayText, lockForUser || current.userLocked(), segmentId, expectedRevision);
        if (changed == 0) throw new ApiException(HttpStatus.CONFLICT, "STALE_REVISION", "Segment revision is stale");
        jdbc.update("INSERT INTO transcript_revisions(id,segment_id,before_text,after_text,actor,reason) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), segmentId, current.displayText(), displayText, actor, reason);
        return findSegment(segmentId);
    }

    public List<TranscriptSegment> transcript(UUID meetingId) {
        find(meetingId);
        return jdbc.query(SEGMENT_SELECT + " WHERE ts.meeting_id=? ORDER BY ts.sequence_no", this::segment, meetingId);
    }

    public long maxTranscriptSequence(UUID meetingId) {
        find(meetingId);
        Long maximum = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM transcript_segments WHERE meeting_id=?",
                Long.class, meetingId);
        return maximum == null ? 0 : maximum;
    }

    public boolean transcriptEventExists(UUID meetingId, String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transcript_segments WHERE meeting_id=? AND provider_event_id=?",
                Integer.class, meetingId, eventId) > 0;
    }

    public TranscriptSegment findSegment(UUID id) {
        List<TranscriptSegment> rows = jdbc.query(SEGMENT_SELECT + " WHERE ts.id=?", this::segment, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SEGMENT_NOT_FOUND", "Transcript segment was not found");
        return rows.getFirst();
    }

    private TranscriptSegment findByEvent(UUID meetingId, String eventId) {
        List<TranscriptSegment> rows = jdbc.query(SEGMENT_SELECT + " WHERE ts.meeting_id=? AND ts.provider_event_id=?", this::segment, meetingId, eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID meetingIdForSegment(UUID segmentId) {
        List<UUID> rows = jdbc.query("SELECT meeting_id FROM transcript_segments WHERE id=?", (rs, n) -> rs.getObject(1, UUID.class), segmentId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SEGMENT_NOT_FOUND", "Transcript segment was not found");
        return rows.getFirst();
    }

    private UUID ensureSpeaker(UUID meetingId, String label, String status) {
        List<UUID> existing = jdbc.query("SELECT id FROM speakers WHERE meeting_id=? AND provider_label=?", (rs, n) -> rs.getObject(1, UUID.class), meetingId, label);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE speakers SET review_state=? WHERE id=?", status, existing.getFirst());
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("INSERT INTO speakers(id,meeting_id,provider_label,review_state) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                id, meetingId, label, status);
        return inserted == 1 ? id : jdbc.queryForObject(
                "SELECT id FROM speakers WHERE meeting_id=? AND provider_label=?", UUID.class, meetingId, label);
    }

    private Meeting meeting(ResultSet rs, int row) throws SQLException {
        return new Meeting(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("status"), rs.getString("input_source"),
                rs.getObject("topic_id", UUID.class),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class), rs.getInt("revision"));
    }

    private TranscriptSegment segment(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new TranscriptSegment(id, id, rs.getString("provider_event_id"), rs.getLong("sequence_no"),
                rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("original_text"), rs.getString("display_text"),
                rs.getString("transcript_status"), rs.getString("provider_label"), rs.getString("speaker_status"),
                rs.getInt("revision"), rs.getBoolean("user_locked"));
    }

    private static final String SEGMENT_SELECT = """
            SELECT ts.id,ts.provider_event_id,ts.sequence_no,ts.start_ms,ts.end_ms,ts.original_text,ts.display_text,
                   ts.transcript_status,s.provider_label,ts.speaker_status,ts.revision,ts.user_locked
              FROM transcript_segments ts LEFT JOIN speakers s ON s.id=ts.speaker_id
            """;
}
