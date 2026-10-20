package dev.aimeeting.api.meeting;

import dev.aimeeting.api.live.LiveWebSocketHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/meetings")
public class MeetingController {
    private final MeetingRepository meetings;
    private final LiveWebSocketHandler live;

    public MeetingController(MeetingRepository meetings, LiveWebSocketHandler live) {
        this.meetings = meetings;
        this.live = live;
    }

    @GetMapping public List<Meeting> list(@RequestParam(required = false) String q) {
        if (q != null && q.length() > 200) {
            throw new dev.aimeeting.api.web.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY", "Search query must not exceed 200 characters");
        }
        if (q == null || q.isBlank()) return meetings.list();
        return meetings.search(q);
    }
    @PostMapping public Meeting create(@Valid @RequestBody CreateMeeting request) {
        return meetings.create(request.title(), request.inputSource() == null ? InputSource.MOCK : request.inputSource(), request.topicId());
    }
    @GetMapping("/{id}") public Meeting get(@PathVariable UUID id) { return meetings.find(id); }
    @PatchMapping("/{id}") public Meeting update(@PathVariable UUID id, @Valid @RequestBody UpdateMeeting request) {
        return meetings.updateTitle(id, request.title(), request.revision());
    }
    @PostMapping("/{id}/start") public Meeting start(@PathVariable UUID id) { return meetings.start(id); }
    @PostMapping("/{id}/stop") public Meeting stop(@PathVariable UUID id) {
        Meeting stopped = meetings.stop(id);
        live.stopMeeting(id);
        return stopped;
    }
    @GetMapping("/{id}/transcript") public List<TranscriptSegment> transcript(@PathVariable UUID id) { return meetings.transcript(id); }

    @PatchMapping("/{meetingId}/segments/{segmentId}")
    public TranscriptSegment correct(@PathVariable UUID meetingId, @PathVariable UUID segmentId,
                                     @Valid @RequestBody CorrectSegment request) {
        return meetings.correct(meetingId, segmentId, request.revision(), request.displayText(), "USER", request.reason(), true);
    }

    public record CreateMeeting(@NotBlank @Size(max = 200) String title, InputSource inputSource, UUID topicId) {}
    public record UpdateMeeting(@NotBlank @Size(max = 200) String title,
                                @NotNull @PositiveOrZero Integer revision) {}
    public record CorrectSegment(@Size(max = 20_000) @NotBlank String displayText, int revision, @Size(max = 300) String reason) {}
}
