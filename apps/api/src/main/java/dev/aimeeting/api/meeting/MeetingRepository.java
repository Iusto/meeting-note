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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MeetingRepository {
    private static final int MAX_FINAL_BATCH_SEGMENTS = 10_000;
    private final JdbcTemplate jdbc;

    public MeetingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record SpeakerName(String label, String displayName, String mergedInto) {
        public SpeakerName(String label, String displayName) { this(label, displayName, null); }
    }

    public record SpeakerOverride(UUID segmentId, String label) {}

    public List<SpeakerOverride> speakerOverrides(UUID meetingId) {
        find(meetingId);
        return jdbc.query("SELECT o.segment_id,o.speaker_label FROM segment_speaker_overrides o JOIN transcript_segments ts ON ts.id=o.segment_id WHERE ts.meeting_id=?",
                (rs,n) -> new SpeakerOverride(rs.getObject(1, UUID.class), rs.getString(2)), meetingId);
    }

    @Transactional
    public List<SpeakerOverride> overrideSpeaker(UUID meetingId, UUID segmentId, String label, String expectedLabel) {
        lock(meetingId);
        if (!meetingId.equals(meetingIdForSegment(segmentId)))
            throw new ApiException(HttpStatus.NOT_FOUND, "SEGMENT_NOT_FOUND", "이 회의의 발언이 아닙니다.");
        var rows = jdbc.query("SELECT speaker_label FROM segment_speaker_overrides WHERE segment_id=?", (rs,n) -> rs.getString(1), segmentId);
        String current = rows.isEmpty() ? null : rows.getFirst();
        if (!Objects.equals(current, expectedLabel))
            throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_OVERRIDE_CHANGED", "발언의 화자가 변경되었습니다. 새로고침 후 다시 시도하세요.");
        if (label == null) jdbc.update("DELETE FROM segment_speaker_overrides WHERE segment_id=?", segmentId);
        else {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM speakers WHERE meeting_id=? AND provider_label=?", Integer.class, meetingId, label) != 1)
                throw new ApiException(HttpStatus.BAD_REQUEST, "SPEAKER_NOT_FOUND", "이 회의에서 감지된 화자를 선택하세요.");
            jdbc.update("DELETE FROM segment_speaker_overrides WHERE segment_id=?", segmentId);
            jdbc.update("INSERT INTO segment_speaker_overrides(segment_id,speaker_label) VALUES (?,?)", segmentId, label);
        }
        return speakerOverrides(meetingId);
    }

    public List<SpeakerName> speakerNames(UUID meetingId) {
        find(meetingId);
        return jdbc.query("SELECT provider_label,display_name,merged_into_label FROM speakers WHERE meeting_id=? AND (display_name IS NOT NULL OR merged_into_label IS NOT NULL) ORDER BY provider_label",
                (rs, n) -> new SpeakerName(rs.getString(1), rs.getString(2), rs.getString(3)), meetingId);
    }

    @Transactional
    public List<SpeakerName> mergeSpeaker(UUID meetingId, String label, String target, String expectedTarget) {
        find(meetingId);
        jdbc.queryForObject("SELECT id FROM meetings WHERE id=? FOR UPDATE", UUID.class, meetingId);
        for (String value : target == null ? List.of(label) : List.of(label, target)) {
            if (value.isBlank() || value.length() > 100 || value.codePoints().anyMatch(Character::isISOControl))
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SPEAKER", "화자 식별자를 확인하세요.");
            jdbc.update("INSERT INTO speakers(id,meeting_id,provider_label,review_state) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                    UUID.randomUUID(), meetingId, value, "TENTATIVE");
        }
        String current = jdbc.queryForObject("SELECT merged_into_label FROM speakers WHERE meeting_id=? AND provider_label=?",
                String.class, meetingId, label);
        if (!Objects.equals(current, expectedTarget))
            throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_MERGE_CHANGED", "화자 연결이 변경되었습니다. 새로고침 후 다시 시도하세요.");
        if (target == null) {
            jdbc.update("UPDATE speakers SET merged_into_label=NULL WHERE meeting_id=? AND provider_label=?", meetingId, label);
        } else {
            String root = jdbc.queryForObject("SELECT COALESCE(merged_into_label,provider_label) FROM speakers WHERE meeting_id=? AND provider_label=?",
                    String.class, meetingId, target);
            String sourceRoot = current == null ? label : current;
            if (sourceRoot.equals(root)) throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_SPEAKER_GROUP", "이미 같은 화자입니다.");
            // Flat groups: moving a group also moves all existing aliases, preventing cycles.
            jdbc.update("UPDATE speakers SET merged_into_label=? WHERE meeting_id=? AND (provider_label=? OR merged_into_label=?)",
                    root, meetingId, sourceRoot, sourceRoot);
        }
        return speakerNames(meetingId);
    }

    @Transactional
    public SpeakerName renameSpeaker(UUID meetingId, String label, String displayName, String expectedName) {
        find(meetingId);
        jdbc.queryForObject("SELECT id FROM meetings WHERE id=? FOR UPDATE", UUID.class, meetingId);
        String name = displayName.strip();
        if (name.codePoints().anyMatch(Character::isISOControl) || label.isBlank()
                || label.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SPEAKER_NAME", "화자 이름과 식별자를 확인하세요.");
        }
        jdbc.update("INSERT INTO speakers(id,meeting_id,provider_label,review_state) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), meetingId, label, "TENTATIVE");
        UUID speakerId = jdbc.queryForObject("SELECT id FROM speakers WHERE meeting_id=? AND provider_label=?",
                UUID.class, meetingId, label);
        int changed = jdbc.update("UPDATE speakers SET display_name=? WHERE id=? AND merged_into_label IS NULL AND COALESCE(display_name,'')=?",
                name.isEmpty() ? null : name, speakerId, expectedName);
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_NAME_CHANGED", "다른 화면에서 화자 이름을 변경했습니다. 새로고침 후 다시 저장하세요.");
        return new SpeakerName(label, name.isEmpty() ? null : name);
    }

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
                    OR EXISTS (SELECT 1 FROM speakers sp LEFT JOIN speakers root
                                  ON root.meeting_id=sp.meeting_id AND root.provider_label=sp.merged_into_label
                                WHERE sp.meeting_id=m.id AND LOWER(CASE WHEN sp.merged_into_label IS NULL
                                  THEN sp.display_name ELSE root.display_name END) LIKE ? ESCAPE '!')
                 ORDER BY m.created_at DESC
                """, this::meeting, pattern, pattern, pattern, pattern);
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
        if (!"LOCAL_RECORDING".equals(active.inputSource()) && !"LIVE".equals(active.inputSource())) return;
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

    @Transactional
    public UUID createDeepgramStreamSession(UUID meetingId, long audioOffsetMs, UUID requestId) {
        return createDeepgramStreamSession(meetingId, audioOffsetMs, requestId, "DEEPGRAM_POC");
    }

    @Transactional
    public UUID createDeepgramStreamSession(UUID meetingId, long audioOffsetMs, UUID requestId, String provider) {
        Objects.requireNonNull(requestId, "requestId");
        if (!"DEEPGRAM_POC".equals(provider) && !"DEEPGRAM".equals(provider)) {
            throw new IllegalArgumentException("Unsupported Deepgram provider");
        }
        if (audioOffsetMs < 0) {
            throw new IllegalArgumentException("audioOffsetMs must be nonnegative");
        }
        Meeting meeting = lock(meetingId);
        if (!"STARTED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_NOT_STARTED", "Meeting must be started first");
        }
        Long confirmedEnd = jdbc.queryForObject(
                "SELECT COALESCE(MAX(end_ms), 0) FROM transcript_segments WHERE meeting_id=? AND transcript_status IN ('FINAL','CORRECTED')",
                Long.class, meetingId);
        long minimumOffset = confirmedEnd == null ? 0 : confirmedEnd;
        if (audioOffsetMs < minimumOffset) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_OFFSET_REPLAY",
                    "Reconnect offset must not precede the last confirmed transcript end");
        }

        UUID sessionId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO stream_sessions(id,meeting_id,provider,audio_offset_ms,started_at) VALUES (?,?,?,?,?)",
                    sessionId, meetingId, provider, audioOffsetMs, OffsetDateTime.now(ZoneOffset.UTC));
            jdbc.update("INSERT INTO active_deepgram_sessions(meeting_id,stream_session_id,request_id) VALUES (?,?,?)",
                    meetingId, sessionId, requestId);
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPTION_ALREADY_ACTIVE",
                    "A Deepgram transcription session is already active");
        }
        return sessionId;
    }

    @Transactional
    public int recoverOrphanedDeepgramSessions() {
        List<ActiveDeepgramClaim> claims = jdbc.query(
                "SELECT meeting_id,stream_session_id,request_id FROM active_deepgram_sessions FOR UPDATE",
                (rs, row) -> new ActiveDeepgramClaim(rs.getObject("meeting_id", UUID.class),
                        rs.getObject("stream_session_id", UUID.class), rs.getObject("request_id", UUID.class)));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (ActiveDeepgramClaim claim : claims) {
            jdbc.update("UPDATE stream_sessions SET ended_at=?,close_reason='APP_RESTART' WHERE id=? AND ended_at IS NULL",
                    now, claim.streamSessionId());
        }
        // Older POC streams predate V8 and have no durable claim. Close their audit rows as well.
        jdbc.update("UPDATE stream_sessions SET ended_at=?,close_reason='APP_RESTART' WHERE provider IN ('DEEPGRAM_POC','DEEPGRAM') AND ended_at IS NULL",
                now);
        // This runs before traffic while holding the process-lifetime database lock. It also repairs a crash
        // between reservation and claim, and conservatively preserves any unlinked dispatched charge.
        jdbc.update("""
                UPDATE ai_usage_records
                   SET state=CASE state WHEN 'RESERVED' THEN 'RELEASED' ELSE 'UNCERTAIN' END,updated_at=?
                 WHERE provider IN ('DEEPGRAM_POC','DEEPGRAM') AND state IN ('RESERVED','DISPATCHED')
                """, now);
        jdbc.update("DELETE FROM active_deepgram_sessions");
        return claims.size();
    }

    private record ActiveDeepgramClaim(UUID meetingId, UUID streamSessionId, UUID requestId) {}

    public void setStreamProviderSessionId(UUID id, String providerSessionId) {
        if (providerSessionId == null || providerSessionId.isBlank() || providerSessionId.length() > 200) {
            throw new IllegalArgumentException("providerSessionId must contain 1 to 200 characters");
        }
        int changed = jdbc.update("UPDATE stream_sessions SET provider_session_id=? WHERE id=? AND (provider_session_id IS NULL OR provider_session_id=?)",
                providerSessionId, id, providerSessionId);
        if (changed != 1) {
            throw new IllegalStateException("Stream provider session ID cannot be replaced");
        }
    }

    @Transactional
    public void setActiveDeepgramProviderSessionId(UUID meetingId, UUID streamSessionId, String providerSessionId) {
        requireActiveDeepgramClaim(meetingId, streamSessionId);
        setStreamProviderSessionId(streamSessionId, providerSessionId);
    }

    @Transactional
    public void closeStreamSession(UUID id, String reason) {
        // Match provider-metadata lock order (active claim -> stream row) to avoid close/update deadlocks.
        jdbc.query("SELECT stream_session_id FROM active_deepgram_sessions WHERE stream_session_id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), id);
        jdbc.update("UPDATE stream_sessions SET ended_at=?,close_reason=? WHERE id=? AND ended_at IS NULL",
                OffsetDateTime.now(ZoneOffset.UTC), reason, id);
        jdbc.update("DELETE FROM active_deepgram_sessions WHERE stream_session_id=?", id);
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

    /**
     * Serializes a provider write against startup recovery/ownership-loss cleanup. Once the durable active claim is
     * removed, an old process cannot append a late final transcript even if a callback was already queued locally.
     */
    @Transactional
    public List<TranscriptSegment> insertActiveDeepgramFinalBatchIfAbsent(
            UUID meetingId, UUID streamSessionId, List<FinalSegmentInput> inputs) {
        // Session creation already uses meeting -> claim. Preserve that order for reconnect/final-write races.
        lock(meetingId);
        requireActiveDeepgramClaim(meetingId, streamSessionId);
        return insertFinalBatchIfAbsent(meetingId, inputs);
    }

    private void requireActiveDeepgramClaim(UUID meetingId, UUID streamSessionId) {
        List<UUID> claims = jdbc.query("""
                SELECT meeting_id FROM active_deepgram_sessions
                 WHERE stream_session_id=? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), streamSessionId);
        if (claims.size() != 1 || !meetingId.equals(claims.getFirst())) {
            throw new IllegalStateException("Deepgram stream ownership is no longer active");
        }
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
