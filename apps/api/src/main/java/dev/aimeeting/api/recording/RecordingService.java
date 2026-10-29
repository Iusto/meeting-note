package dev.aimeeting.api.recording;

import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.storage.RecordingStorage;
import dev.aimeeting.api.web.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordingService {
    public static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHUNKS = 1_000_000;
    private static final long MAX_TIMESTAMP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final MeetingRepository meetings;
    private final RecordingRepository recordings;
    private final RecordingStorage storage;

    public RecordingService(MeetingRepository meetings, RecordingRepository recordings, RecordingStorage storage) {
        this.meetings = meetings;
        this.recordings = recordings;
        this.storage = storage;
    }

    @Transactional
    public RecordingChunkResponse upload(UUID meetingId, int sequence, long startMs, long endMs,
                                         String expectedSha256, String mimeType, byte[] content) {
        validateChunkRequest(sequence, startMs, endMs, expectedSha256, mimeType, content);
        requireActiveOrStoppedLocalMeeting(meetingId);
        String normalizedSha = expectedSha256.toLowerCase(Locale.ROOT);
        String actualSha = sha256(content);
        if (!constantTimeEquals(normalizedSha, actualSha)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHUNK_HASH_MISMATCH", "Chunk SHA-256 does not match its content");
        }

        RecordingMetadata recording = recordings.ensureAndLock(meetingId);
        RecordingChunk existing = recordings.findChunk(recording.id(), sequence);
        if (existing != null) {
            if (sameChunk(existing, startMs, endMs, content.length, normalizedSha, mimeType)) return existing.response();
            throw new ApiException(HttpStatus.CONFLICT, "CHUNK_CONFLICT", "A different chunk already uses this sequence");
        }
        if ("COMPLETED".equals(recording.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "RECORDING_COMPLETED", "Completed recordings cannot accept more chunks");
        }

        try {
            Path path = storage.writeChunk(meetingId, sequence, content);
            RecordingChunk chunk = new RecordingChunk(sequence, startMs, endMs, content.length, normalizedSha, mimeType, path);
            recordings.insertChunk(recording.id(), chunk);
            return chunk.response();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORDING_STORAGE_FAILED", "Recording data could not be stored");
        }
    }

    @Transactional
    public RecordingMetadata complete(UUID meetingId, int chunkCount) {
        if (chunkCount <= 0 || chunkCount > MAX_CHUNKS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHUNK_COUNT", "Chunk count is outside the allowed range");
        }
        requireActiveOrStoppedLocalMeeting(meetingId);
        RecordingMetadata recording = recordings.lock(meetingId);
        if ("COMPLETED".equals(recording.state())) {
            if (recording.chunkCount() == chunkCount) return recording;
            throw new ApiException(HttpStatus.CONFLICT, "RECORDING_COMPLETED", "Recording was completed with a different chunk count");
        }

        List<RecordingChunk> chunks = recordings.chunks(recording.id());
        if (chunks.size() != chunkCount) missingChunks();
        String mimeType = chunks.getFirst().mimeType();
        long durationMs = 0;
        for (int sequence = 0; sequence < chunkCount; sequence++) {
            RecordingChunk chunk = chunks.get(sequence);
            if (chunk.sequence() != sequence) missingChunks();
            if (!mimeType.equals(chunk.mimeType())) {
                throw new ApiException(HttpStatus.CONFLICT, "MIXED_RECORDING_FORMAT", "All recording chunks must use the same media type");
            }
            durationMs = Math.max(durationMs, chunk.endMs());
        }

        try {
            RecordingStorage.AssembledRecording assembled = storage.assemble(meetingId,
                    chunks.stream().map(chunk -> new RecordingStorage.StoredChunk(
                            chunk.path(), chunk.bytes(), chunk.sha256())).toList());
            return recordings.complete(recording.id(), chunkCount, assembled.bytes(), durationMs,
                    assembled.sha256(), mimeType);
        } catch (RecordingStorage.RecordingIntegrityException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "CHUNK_INTEGRITY_FAILED", "Stored recording chunks failed integrity verification");
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORDING_STORAGE_FAILED", "Recording could not be finalized");
        }
    }

    public RecordingMetadata metadata(UUID meetingId) {
        meetings.find(meetingId);
        return recordings.find(meetingId);
    }

    public CompletedAudio audio(UUID meetingId) {
        Meeting meeting = meetings.find(meetingId);
        RecordingMetadata recording = recordings.find(meetingId);
        if (!"COMPLETED".equals(recording.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "RECORDING_INCOMPLETE", "Recording is not complete");
        }
        try {
            Path path = storage.requireStoredPath(meeting.id(), storage.resolve(meeting.id(), "recording.bin"));
            return new CompletedAudio(path, recording.mimeType(), recording.bytes());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORDING_STORAGE_FAILED", "Recording audio is unavailable");
        }
    }

    private Meeting requireActiveOrStoppedLocalMeeting(UUID meetingId) {
        Meeting meeting = meetings.lock(meetingId);
        if (!"LOCAL_RECORDING".equals(meeting.inputSource()) && !"LIVE".equals(meeting.inputSource())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_INPUT_SOURCE", "Meeting does not accept local recording chunks");
        }
        if (!"STARTED".equals(meeting.status()) && !"STOPPED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_NOT_STARTED", "Meeting must be started before recording upload");
        }
        return meeting;
    }

    private void validateChunkRequest(int sequence, long startMs, long endMs, String expectedSha256,
                                      String mimeType, byte[] content) {
        if (sequence < 0 || sequence >= MAX_CHUNKS || startMs < 0 || endMs < startMs || endMs > MAX_TIMESTAMP_MS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHUNK_METADATA", "Chunk sequence or timestamps are invalid");
        }
        if (content.length == 0 || content.length > MAX_CHUNK_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "INVALID_CHUNK_SIZE", "Chunk size is outside the allowed range");
        }
        if (expectedSha256 == null || !SHA256.matcher(expectedSha256).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHUNK_HASH", "Chunk SHA-256 must contain 64 hexadecimal characters");
        }
        if (mimeType == null || mimeType.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_TYPE", "Chunk media type is invalid");
        }
        try {
            MediaType.parseMediaType(mimeType);
        } catch (InvalidMediaTypeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_TYPE", "Chunk media type is invalid");
        }
    }

    private boolean sameChunk(RecordingChunk existing, long startMs, long endMs, long bytes,
                              String sha256, String mimeType) {
        return existing.startMs() == startMs && existing.endMs() == endMs && existing.bytes() == bytes
                && existing.sha256().equals(sha256) && existing.mimeType().equals(mimeType);
    }

    private void missingChunks() {
        throw new ApiException(HttpStatus.CONFLICT, "RECORDING_CHUNKS_MISSING", "Recording has missing chunks");
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    public record CompletedAudio(Path path, String mimeType, long bytes) {}
}
