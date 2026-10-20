package dev.aimeeting.api.summary;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/summary")
public class SummaryController {
    private final SummaryService summaries;

    public SummaryController(SummaryService summaries) { this.summaries = summaries; }

    @GetMapping public MeetingSummary latest(@PathVariable UUID meetingId) { return summaries.latest(meetingId); }
    @PostMapping public MeetingSummary generate(@PathVariable UUID meetingId) { return summaries.generate(meetingId); }
}
