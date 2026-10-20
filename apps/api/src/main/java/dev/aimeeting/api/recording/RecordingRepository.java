package dev.aimeeting.api.recording;

import dev.aimeeting.api.web.ApiException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecordingRepository {
    private final JdbcTemplate jdbc;

    public RecordingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RecordingMetadata find(UUID meetingId) {
        List<RecordingMetadata> rows = jdbc.query(METADATA_SELECT + " WHERE meeting_id=?", this::metadata, meetingId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "Recording was not found");
        return rows.getFirst();
    }

    public RecordingMetadata ensureAndLock(UUID meetingId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recordings(id,meeting_id,state,path,chunk_count) VALUES (?,?,?,'recording.bin',0) ON CONFLICT DO NOTHING",
                id, meetingId, "UPLOADING");
        return jdbc.queryForObject(METADATA_SELECT + " WHERE meeting_id=? FOR UPDATE", this::metadata, meetingId);
    }

    public RecordingMetadata lock(UUID meetingId) {
        List<RecordingMetadata> rows = jdbc.query(METADATA_SELECT + " WHERE meeting_id=? FOR UPDATE", this::metadata, meetingId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "Recording was not found");
        return rows.getFirst();
    }

    public RecordingChunk findChunk(UUID recordingId, int sequence) {
        List<RecordingChunk> rows = jdbc.query(CHUNK_SELECT + " WHERE recording_id=? AND sequence_no=?", this::chunk,
                recordingId, sequence);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<RecordingChunk> chunks(UUID recordingId) {
        return jdbc.query(CHUNK_SELECT + " WHERE recording_id=? ORDER BY sequence_no", this::chunk, recordingId);
    }

    public void insertChunk(UUID recordingId, RecordingChunk chunk) {
        jdbc.update("INSERT INTO recording_chunks(recording_id,sequence_no,start_ms,end_ms,sha256,mime_type,bytes,path) VALUES (?,?,?,?,?,?,?,?)",
                recordingId, chunk.sequence(), chunk.startMs(), chunk.endMs(), chunk.sha256(), chunk.mimeType(),
                chunk.bytes(), chunk.path().toAbsolutePath().normalize().toString());
    }

    public RecordingMetadata complete(UUID recordingId, int chunkCount, long bytes, long durationMs,
                                      String sha256, String mimeType) {
        jdbc.update("UPDATE recordings SET state='COMPLETED',path='recording.bin',chunk_count=?,bytes=?,duration_ms=?,sha256=?,mime_type=?,completed_at=? WHERE id=?",
                chunkCount, bytes, durationMs, sha256, mimeType, OffsetDateTime.now(ZoneOffset.UTC), recordingId);
        return jdbc.queryForObject(METADATA_SELECT + " WHERE id=?", this::metadata, recordingId);
    }

    private RecordingMetadata metadata(ResultSet rs, int row) throws SQLException {
        return new RecordingMetadata(rs.getObject("id", UUID.class), rs.getObject("meeting_id", UUID.class),
                rs.getString("state"), (Long) rs.getObject("bytes"), rs.getString("sha256"),
                (Long) rs.getObject("duration_ms"), rs.getString("mime_type"), rs.getInt("chunk_count"));
    }

    private RecordingChunk chunk(ResultSet rs, int row) throws SQLException {
        return new RecordingChunk(rs.getInt("sequence_no"), rs.getLong("start_ms"), rs.getLong("end_ms"),
                rs.getLong("bytes"), rs.getString("sha256"), rs.getString("mime_type"), Path.of(rs.getString("path")));
    }

    private static final String METADATA_SELECT = "SELECT id,meeting_id,state,bytes,sha256,duration_ms,mime_type,chunk_count FROM recordings";
    private static final String CHUNK_SELECT = "SELECT sequence_no,start_ms,end_ms,bytes,sha256,mime_type,path FROM recording_chunks";
}
