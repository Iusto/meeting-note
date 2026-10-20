package dev.aimeeting.api.recording;

import java.util.UUID;

public record RecordingMetadata(UUID id, UUID meetingId, String state, Long bytes, String sha256,
                                Long durationMs, String mimeType, int chunkCount) {}
