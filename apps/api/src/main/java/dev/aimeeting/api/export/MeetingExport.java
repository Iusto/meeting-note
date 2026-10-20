package dev.aimeeting.api.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.summary.MeetingSummary;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MeetingExport(int schemaVersion, Meeting meeting, List<ExportTranscriptSegment> transcript,
                            MeetingSummary summary) {
    public record ExportTranscriptSegment(UUID id, String eventId, long sequence, long startMs, long endMs,
                                          String originalText, String displayText, String transcriptStatus,
                                          String speaker, String speakerStatus, int revision, boolean userLocked) {}
}
