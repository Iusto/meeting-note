package dev.aimeeting.api.provider;

import dev.aimeeting.api.meeting.TranscriptSegment;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FakeSummaryProvider implements SummaryProvider {
    @Override
    public Summary summarize(UUID meetingId, List<TranscriptSegment> transcript) {
        List<TranscriptSegment> evidence = transcript.stream().limit(3).toList();
        String extractive = evidence.stream().map(TranscriptSegment::displayText)
                .reduce((left, right) -> left + " " + right).orElse("");
        return new Summary(extractive, evidence.stream().map(TranscriptSegment::id).toList(),
                List.of(), List.of(), List.of());
    }
}
