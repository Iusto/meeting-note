package dev.aimeeting.api.export;

import dev.aimeeting.api.web.ApiException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/export")
public class MeetingExportController {
    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");
    private final MeetingExportService exports;

    public MeetingExportController(MeetingExportService exports) { this.exports = exports; }

    @GetMapping
    public ResponseEntity<byte[]> export(@PathVariable UUID meetingId, @RequestParam String format) {
        if (!"json".equals(format) && !"markdown".equals(format)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_FORMAT",
                    "Export format must be json or markdown");
        }
        MeetingExport snapshot = exports.snapshot(meetingId);
        return switch (format) {
            case "json" -> attachment(exports.json(snapshot), MediaType.APPLICATION_JSON, "meeting-" + meetingId + ".json");
            case "markdown" -> attachment(exports.markdown(snapshot), MARKDOWN, "meeting-" + meetingId + ".md");
            default -> throw new IllegalStateException("Validated export format was not handled");
        };
    }

    private ResponseEntity<byte[]> attachment(byte[] content, MediaType mediaType, String filename) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }
}
