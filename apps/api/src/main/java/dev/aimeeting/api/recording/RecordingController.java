package dev.aimeeting.api.recording;

import dev.aimeeting.api.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}")
public class RecordingController {
    private final RecordingService recordings;

    public RecordingController(RecordingService recordings) {
        this.recordings = recordings;
    }

    @PostMapping(path = "/recording-chunks", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public RecordingChunkResponse upload(@PathVariable UUID meetingId,
                                         @RequestParam int sequence,
                                         @RequestParam long startMs,
                                         @RequestParam long endMs,
                                         @RequestParam String sha256,
                                         @RequestParam String mimeType,
                                         HttpServletRequest request) throws IOException {
        return recordings.upload(meetingId, sequence, startMs, endMs, sha256, mimeType, readBounded(request));
    }

    @PostMapping(path = "/recording-complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecordingMetadata complete(@PathVariable UUID meetingId, @Valid @RequestBody CompleteRecording request) {
        return recordings.complete(meetingId, request.chunkCount());
    }

    @GetMapping("/recording")
    public RecordingMetadata metadata(@PathVariable UUID meetingId) {
        return recordings.metadata(meetingId);
    }

    @GetMapping("/recording/audio")
    public ResponseEntity<FileSystemResource> audio(@PathVariable UUID meetingId) {
        RecordingService.CompletedAudio audio = recordings.audio(meetingId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(audio.mimeType());
        } catch (Exception ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        String extension = switch ((mediaType.getType() + "/" + mediaType.getSubtype()).toLowerCase(Locale.ROOT)) {
            case "audio/webm" -> "webm";
            case "audio/ogg" -> "ogg";
            default -> "bin";
        };
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(audio.bytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("meeting-%s-recording.%s".formatted(meetingId, extension)).build().toString())
                .body(new FileSystemResource(audio.path()));
    }

    private byte[] readBounded(HttpServletRequest request) throws IOException {
        try (InputStream input = request.getInputStream()) {
            byte[] content = input.readNBytes(RecordingService.MAX_CHUNK_BYTES + 1);
            if (content.length > RecordingService.MAX_CHUNK_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "INVALID_CHUNK_SIZE", "Chunk exceeds 8 MiB");
            }
            return content;
        }
    }

    public record CompleteRecording(@Min(1) @Max(1_000_000) int chunkCount) {}
}
