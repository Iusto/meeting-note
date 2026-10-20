package dev.aimeeting.api.meeting;

import java.util.UUID;

public record TranscriptSegment(
        UUID id,
        UUID segmentId,
        String eventId,
        long sequence,
        long startMs,
        long endMs,
        String originalText,
        String displayText,
        String transcriptStatus,
        String speakerLabel,
        String speakerStatus,
        int revision,
        boolean userLocked) {}
