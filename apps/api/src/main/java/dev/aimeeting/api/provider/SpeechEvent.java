package dev.aimeeting.api.provider;

import java.util.UUID;

public record SpeechEvent(
        String type,
        UUID meetingId,
        UUID streamSessionId,
        String eventId,
        Long sequence,
        Long startMs,
        Long endMs,
        String text,
        String speakerLabel,
        String speakerStatus,
        UUID segmentId,
        Integer revision,
        String originalText,
        String displayText,
        String transcriptStatus,
        String message) {

    public SpeechEvent(String type, UUID meetingId, UUID streamSessionId, String eventId, Long sequence,
                       Long startMs, Long endMs, String text, String speakerLabel, String speakerStatus,
                       UUID segmentId, Integer revision, String originalText, String displayText, String transcriptStatus) {
        this(type, meetingId, streamSessionId, eventId, sequence, startMs, endMs, text, speakerLabel,
                speakerStatus, segmentId, revision, originalText, displayText, transcriptStatus, null);
    }

    public static SpeechEvent sessionReady(UUID meetingId, UUID streamSessionId) {
        return new SpeechEvent("session.ready", meetingId, streamSessionId, null, 0L, null, null,
                null, null, null, null, null, null, null, null);
    }
}
