package dev.aimeeting.api.summary;

import dev.aimeeting.api.provider.openai.MeetingAiApplicationService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/summary")
public class SummaryController {
    private final SummaryService summaries;
    private final MeetingAiApplicationService ai;

    public SummaryController(SummaryService summaries, MeetingAiApplicationService ai) {
        this.summaries = summaries;
        this.ai = ai;
    }

    @GetMapping public MeetingSummary latest(@PathVariable UUID meetingId) { return summaries.latest(meetingId); }
    @PostMapping public CompletableFuture<MeetingSummary> generate(@PathVariable UUID meetingId) {
        return ai.enabled() ? ai.generateSummary(meetingId)
                : CompletableFuture.completedFuture(summaries.generate(meetingId));
    }
}
