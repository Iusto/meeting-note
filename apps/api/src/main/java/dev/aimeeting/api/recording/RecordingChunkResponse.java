package dev.aimeeting.api.recording;

public record RecordingChunkResponse(int sequence, long startMs, long endMs, long bytes,
                                     String sha256, String mimeType) {}
