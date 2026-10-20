package dev.aimeeting.api.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.provider.SummaryProvider;
import dev.aimeeting.api.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SummaryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SummaryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void lockMeeting(UUID meetingId) {
        List<UUID> rows = jdbc.query("SELECT id FROM meetings WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), meetingId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "Meeting was not found");
    }

    public StoredSummary latest(UUID meetingId) {
        StoredSummary latest = latestOrNull(meetingId);
        if (latest == null) throw new ApiException(HttpStatus.NOT_FOUND, "SUMMARY_NOT_FOUND", "Meeting summary was not found");
        return latest;
    }

    public StoredSummary latestOrNull(UUID meetingId) {
        List<StoredSummary> rows = jdbc.query("SELECT id,meeting_id,version,provider,content_json,snapshot_json " +
                        "FROM meeting_summaries WHERE meeting_id=? ORDER BY version DESC LIMIT 1",
                (rs, row) -> decode(rs.getObject("id", UUID.class), rs.getObject("meeting_id", UUID.class),
                        rs.getInt("version"), rs.getString("provider"), rs.getString("content_json"), rs.getString("snapshot_json")),
                meetingId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int nextVersion(UUID meetingId) {
        Integer latest = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM meeting_summaries WHERE meeting_id=?",
                Integer.class, meetingId);
        return (latest == null ? 0 : latest) + 1;
    }

    public StoredSummary insert(UUID meetingId, int version, SummaryProvider.Summary content,
                                List<SnapshotEntry> snapshot) {
        return insert(meetingId, version, "MOCK", content, snapshot);
    }

    public StoredSummary insert(UUID meetingId, int version, String provider, SummaryProvider.Summary content,
                                List<SnapshotEntry> snapshot) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO meeting_summaries(id,meeting_id,version,provider,content_json,snapshot_json) VALUES (?,?,?,?,?,?)",
                    id, meetingId, version, provider, mapper.writeValueAsString(content), mapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Summary serialization failed", exception);
        }
        return latest(meetingId);
    }

    private StoredSummary decode(UUID id, UUID meetingId, int version, String provider,
                                 String contentJson, String snapshotJson) {
        try {
            return new StoredSummary(id, meetingId, version, provider,
                    mapper.readValue(contentJson, SummaryProvider.Summary.class),
                    mapper.readValue(snapshotJson, new TypeReference<List<SnapshotEntry>>() {}));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored summary could not be decoded", exception);
        }
    }

    public record SnapshotEntry(UUID segmentId, int revision) {}
    public record StoredSummary(UUID id, UUID meetingId, int version, String provider,
                                SummaryProvider.Summary content, List<SnapshotEntry> snapshot) {}
}
