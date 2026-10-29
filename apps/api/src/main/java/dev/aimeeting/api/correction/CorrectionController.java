package dev.aimeeting.api.correction;

import dev.aimeeting.api.provider.openai.MeetingAiApplicationService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/correction-suggestions")
public class CorrectionController {
    private final CorrectionService corrections;
    private final MeetingAiApplicationService ai;

    public CorrectionController(CorrectionService corrections, MeetingAiApplicationService ai) {
        this.corrections = corrections;
        this.ai = ai;
    }

    @GetMapping public List<CorrectionSuggestion> list(@PathVariable UUID meetingId) { return corrections.list(meetingId); }
    @PostMapping public CompletableFuture<List<CorrectionSuggestion>> generate(@PathVariable UUID meetingId) {
        return ai.enabled() ? ai.generateCorrections(meetingId)
                : CompletableFuture.completedFuture(corrections.generate(meetingId));
    }
    @PatchMapping("/{suggestionId}") public CorrectionSuggestion review(
            @PathVariable UUID meetingId, @PathVariable UUID suggestionId, @RequestBody ReviewRequest request) {
        return corrections.review(meetingId, suggestionId, request.state());
    }

    public record ReviewRequest(String state) {}
}
