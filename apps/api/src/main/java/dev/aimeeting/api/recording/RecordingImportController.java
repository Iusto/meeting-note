package dev.aimeeting.api.recording;

import dev.aimeeting.api.meeting.Meeting;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings")
public class RecordingImportController {
    private final RecordingImportService imports;

    public RecordingImportController(RecordingImportService imports) {
        this.imports = imports;
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Meeting submit(@RequestParam String title, @RequestParam(required = false) UUID topicId,
                          @RequestParam String format, HttpServletRequest request) throws IOException {
        try (var input = request.getInputStream()) {
            return imports.submit(title, topicId, format, input, request.getContentLengthLong());
        }
    }

    @GetMapping("/{meetingId}/import")
    public RecordingImportService.Status status(@PathVariable UUID meetingId) {
        return imports.status(meetingId);
    }

    @PostMapping("/{meetingId}/import/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecordingImportService.Status retry(@PathVariable UUID meetingId) {
        return imports.retry(meetingId);
    }
}
