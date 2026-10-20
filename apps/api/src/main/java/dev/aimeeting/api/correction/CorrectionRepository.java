package dev.aimeeting.api.correction;

import dev.aimeeting.api.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CorrectionRepository {
    private final JdbcTemplate jdbc;

    public CorrectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public CorrectionSuggestion insert(UUID meetingId, UUID segmentId, int expectedRevision, int spanStart,
                                       String originalSpan, String replacement, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO correction_suggestions(id,meeting_id,segment_id,expected_revision,span_start,original_span,replacement,reason,state) " +
                        "VALUES (?,?,?,?,?,?,?,?,'PENDING') ON CONFLICT DO NOTHING",
                id, meetingId, segmentId, expectedRevision, spanStart, originalSpan, replacement, reason);
        return jdbc.queryForObject(SELECT + " WHERE meeting_id=? AND segment_id=? AND expected_revision=? AND span_start=? AND original_span=? AND replacement=?",
                this::map, meetingId, segmentId, expectedRevision, spanStart, originalSpan, replacement);
    }

    public List<CorrectionSuggestion> list(UUID meetingId) {
        return jdbc.query(SELECT + " WHERE meeting_id=? ORDER BY id", this::map, meetingId);
    }

    public StoredCorrection lock(UUID meetingId, UUID id) {
        List<StoredCorrection> rows = jdbc.query("SELECT id,meeting_id,segment_id,expected_revision,span_start,original_span,replacement,reason,state " +
                        "FROM correction_suggestions WHERE id=? AND meeting_id=? FOR UPDATE",
                (rs, row) -> new StoredCorrection(rs.getObject("id", UUID.class), rs.getObject("meeting_id", UUID.class),
                        rs.getObject("segment_id", UUID.class), rs.getInt("expected_revision"), rs.getInt("span_start"),
                        rs.getString("original_span"), rs.getString("replacement"), rs.getString("reason"), rs.getString("state")),
                id, meetingId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "CORRECTION_NOT_FOUND", "Correction suggestion was not found");
        return rows.getFirst();
    }

    public CorrectionSuggestion setState(UUID meetingId, UUID id, String state) {
        jdbc.update("UPDATE correction_suggestions SET state=? WHERE id=? AND meeting_id=?", state, id, meetingId);
        return jdbc.queryForObject(SELECT + " WHERE id=? AND meeting_id=?", this::map, id, meetingId);
    }

    private CorrectionSuggestion map(ResultSet rs, int row) throws SQLException {
        return new CorrectionSuggestion(rs.getObject("id", UUID.class), rs.getObject("segment_id", UUID.class),
                rs.getInt("expected_revision"), rs.getString("original_span"), rs.getString("replacement"),
                rs.getString("reason"), rs.getString("state"));
    }

    public record StoredCorrection(UUID id, UUID meetingId, UUID segmentId, int expectedRevision, int spanStart,
                                   String originalSpan, String replacement, String reason, String state) {}

    private static final String SELECT = "SELECT id,segment_id,expected_revision,original_span,replacement,reason,state FROM correction_suggestions";
}
