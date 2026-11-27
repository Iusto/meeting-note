package dev.aimeeting.api.provider.whisper;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.meeting.MeetingRepository.FinalSegmentInput;
import dev.aimeeting.api.web.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WhisperClient {
    private static final URI LOCAL_SERVICE = URI.create("http://whisper:8768/");
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;

    public WhisperClient(ObjectMapper mapper) { this.mapper = mapper; }

    void requireReady() {
        var request = HttpRequest.newBuilder(LOCAL_SERVICE.resolve("health")).timeout(Duration.ofSeconds(2)).GET().build();
        try {
            if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) throw unavailable();
        } catch (IOException failure) {
            throw unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    public List<FinalSegmentInput> transcribeFile(Path pcm, long durationMs) throws IOException, InterruptedException {
        List<ClipText> clips = transcribe(HttpRequest.BodyPublishers.ofFile(pcm), durationMs, Duration.ofHours(2));
        var results = new ArrayList<FinalSegmentInput>(clips.size());
        for (ClipText clip : clips) {
            long sequence = results.size() + 1L;
            results.add(new FinalSegmentInput("whisper-import:" + sequence, sequence,
                    clip.startMs(), clip.endMs(), clip.text(), null, "UNKNOWN"));
        }
        return List.copyOf(results);
    }

    List<ClipText> transcribeLive(byte[] pcm) throws IOException, InterruptedException {
        return transcribe(HttpRequest.BodyPublishers.ofByteArray(pcm), (pcm.length + 31L) / 32L, Duration.ofSeconds(25));
    }

    private List<ClipText> transcribe(HttpRequest.BodyPublisher pcm, long durationMs, Duration timeout)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(LOCAL_SERVICE.resolve("transcribe")).timeout(timeout)
                .header("Content-Type", "application/octet-stream").POST(pcm).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) throw unavailable();
                byte[] data = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (data.length > MAX_RESPONSE_BYTES) throw unavailable();
                var result = mapper.readTree(data).path("segments");
                if (!result.isArray() || result.size() > 10_000) throw unavailable();
                List<ClipText> clips = new ArrayList<>(result.size());
                for (var segment : result) {
                    long start = segment.path("startMs").asLong(-1);
                    long end = segment.path("endMs").asLong(-1);
                    var text = segment.path("text").asText("").strip();
                    if (start < 0 || end < start || end > durationMs || text.isEmpty()) throw unavailable();
                    clips.add(new ClipText(start, end, text));
                }
                return List.copyOf(clips);
            }
        } catch (IOException error) {
            throw unavailable();
        }
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WHISPER_UNAVAILABLE",
                "로컬 Whisper 전사에 연결하지 못했습니다. 서버에서 모델이 실행 중인지 확인하세요.");
    }

    record ClipText(long startMs, long endMs, String text) {}
}
