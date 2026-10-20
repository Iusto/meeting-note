package dev.aimeeting.api.meeting;

import dev.aimeeting.api.meeting.MeetingDeletionService.DeletionResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings")
public class MeetingDeletionController {
    private final MeetingDeletionService deletions;

    public MeetingDeletionController(MeetingDeletionService deletions) { this.deletions = deletions; }

    @DeleteMapping("/{id}")
    public DeletionResult delete(@PathVariable UUID id, @RequestParam int revision) {
        return deletions.delete(id, revision);
    }
}
