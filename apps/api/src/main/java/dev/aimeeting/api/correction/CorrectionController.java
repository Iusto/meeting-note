package dev.aimeeting.api.correction;

import java.util.List;
import java.util.UUID;
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

    public CorrectionController(CorrectionService corrections) { this.corrections = corrections; }

    @GetMapping public List<CorrectionSuggestion> list(@PathVariable UUID meetingId) { return corrections.list(meetingId); }
    @PostMapping public List<CorrectionSuggestion> generate(@PathVariable UUID meetingId) { return corrections.generate(meetingId); }
    @PatchMapping("/{suggestionId}") public CorrectionSuggestion review(
            @PathVariable UUID meetingId, @PathVariable UUID suggestionId, @RequestBody ReviewRequest request) {
        return corrections.review(meetingId, suggestionId, request.state());
    }

    public record ReviewRequest(String state) {}
}
