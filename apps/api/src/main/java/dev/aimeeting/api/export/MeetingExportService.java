package dev.aimeeting.api.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.export.MeetingExport.ExportTranscriptSegment;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.provider.SummaryProvider;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.summary.SummaryService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingExportService {
    private final MeetingRepository meetings;
    private final SummaryService summaries;
    private final ObjectMapper mapper;

    public MeetingExportService(MeetingRepository meetings, SummaryService summaries, ObjectMapper mapper) {
        this.meetings = meetings;
        this.summaries = summaries;
        this.mapper = mapper;
    }

    @Transactional
    public MeetingExport snapshot(UUID meetingId) {
        Meeting meeting = meetings.lock(meetingId);
        List<ExportTranscriptSegment> transcript = meetings.transcript(meetingId).stream().map(this::exportSegment).toList();
        MeetingSummary summary = summaries.latestOrNull(meetingId);
        return new MeetingExport(1, meeting, transcript, summary);
    }

    public byte[] json(MeetingExport export) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Meeting export serialization failed", exception);
        }
    }

    public byte[] markdown(MeetingExport export) {
        StringBuilder output = new StringBuilder();
        Meeting meeting = export.meeting();
        output.append("# ").append(escape(meeting.title())).append("\n\n")
                .append("- ID: ").append(meeting.id()).append('\n')
                .append("- Status: ").append(meeting.status()).append('\n')
                .append("- Input source: ").append(meeting.inputSource()).append('\n')
                .append("- Revision: ").append(meeting.revision()).append("\n\n")
                .append("## Transcript\n\n");
        if (export.transcript().isEmpty()) output.append("No transcript.\n\n");
        for (ExportTranscriptSegment segment : export.transcript()) {
            output.append("### ").append(escape(segment.speaker())).append(" · ")
                    .append(segment.startMs()).append("–").append(segment.endMs()).append(" ms\n\n")
                    .append("- Segment ID: ").append(segment.id()).append('\n')
                    .append("- Original: ").append(escape(segment.originalText())).append('\n')
                    .append("- Display: ").append(escape(segment.displayText())).append('\n')
                    .append("- Transcript status: ").append(segment.transcriptStatus()).append('\n')
                    .append("- Speaker status: ").append(segment.speakerStatus()).append('\n')
                    .append("- Revision: ").append(segment.revision()).append('\n')
                    .append("- User locked: ").append(segment.userLocked()).append("\n\n");
        }
        output.append("## Summary\n\n");
        if (export.summary() == null) {
            output.append("No summary.\n");
        } else {
            appendSummary(output, export.summary());
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private ExportTranscriptSegment exportSegment(TranscriptSegment segment) {
        String speaker = segment.speakerLabel() == null || segment.speakerLabel().isBlank()
                ? "unknown" : segment.speakerLabel();
        return new ExportTranscriptSegment(segment.id(), segment.eventId(), segment.sequence(), segment.startMs(),
                segment.endMs(), segment.originalText(), segment.displayText(), segment.transcriptStatus(), speaker,
                segment.speakerStatus(), segment.revision(), segment.userLocked());
    }

    private void appendSummary(StringBuilder output, MeetingSummary summary) {
        SummaryProvider.Summary content = summary.content();
        output.append("- Version: ").append(summary.version()).append('\n')
                .append("- Provider: ").append(summary.provider()).append('\n')
                .append("- Stale: ").append(summary.stale()).append("\n\n")
                .append(escape(content.summary())).append("\n\n")
                .append("Source segments: ").append(content.sourceSegmentIds()).append("\n\n");
        appendEvidence(output, "Decisions", content.decisions());
        output.append("### Action items\n\n");
        for (SummaryProvider.ActionItem item : content.actionItems()) {
            output.append("- ").append(escape(item.text())).append(" (sources: ")
                    .append(item.sourceSegmentIds()).append(", assignee: ")
                    .append(escape(unknownIfBlank(item.assignee()))).append(", due date: ")
                    .append(escape(unknownIfBlank(item.dueDate()))).append(")\n");
        }
        if (content.actionItems().isEmpty()) output.append("None.\n");
        output.append('\n');
        appendEvidence(output, "Unresolved", content.unresolved());
    }

    private void appendEvidence(StringBuilder output, String heading, List<SummaryProvider.Evidence> items) {
        output.append("### ").append(heading).append("\n\n");
        for (SummaryProvider.Evidence item : items) {
            output.append("- ").append(escape(item.text())).append(" (sources: ")
                    .append(item.sourceSegmentIds()).append(")\n");
        }
        if (items.isEmpty()) output.append("None.\n");
        output.append('\n');
    }

    static String escape(String value) {
        if (value == null) return "";
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder escaped = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '\n' -> escaped.append("  \n");
                case '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|' ->
                        escaped.append('\\').append(character);
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private String unknownIfBlank(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
