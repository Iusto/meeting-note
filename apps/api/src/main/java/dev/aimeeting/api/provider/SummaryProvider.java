package dev.aimeeting.api.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.aimeeting.api.meeting.TranscriptSegment;
import java.util.List;
import java.util.UUID;

public interface SummaryProvider {
    Summary summarize(UUID meetingId, List<TranscriptSegment> transcript);
    record Summary(String summary, List<UUID> sourceSegmentIds, List<Evidence> decisions,
                   List<ActionItem> actionItems, List<Evidence> unresolved) {}
    record Evidence(String text, List<UUID> sourceSegmentIds) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ActionItem(String text, String assignee, String dueDate, List<UUID> sourceSegmentIds) {}
}
