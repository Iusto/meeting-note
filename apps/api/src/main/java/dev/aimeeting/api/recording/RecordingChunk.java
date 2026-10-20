package dev.aimeeting.api.recording;

import java.nio.file.Path;

public record RecordingChunk(int sequence, long startMs, long endMs, long bytes,
                             String sha256, String mimeType, Path path) {
    public RecordingChunkResponse response() {
        return new RecordingChunkResponse(sequence, startMs, endMs, bytes, sha256, mimeType);
    }
}
