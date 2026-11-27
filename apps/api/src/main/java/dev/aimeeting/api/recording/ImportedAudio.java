package dev.aimeeting.api.recording;

import dev.aimeeting.api.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class ImportedAudio {
    private static final int BYTES_PER_MILLISECOND = 32;

    enum Format {
        M4A("mov", "audio/mp4"), MP3("mp3", "audio/mpeg"), WAV("wav", "audio/wav"),
        FLAC("flac", "audio/flac"), OGG("ogg", "audio/ogg"), WEBM("matroska", "audio/webm");

        final String demuxer;
        final String mimeType;

        Format(String demuxer, String mimeType) {
            this.demuxer = demuxer;
            this.mimeType = mimeType;
        }

        static Format parse(String extension) {
            try {
                return valueOf(extension.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO_FORMAT",
                        "M4A, MP3, WAV, FLAC, OGG 또는 WEBM 녹음 파일을 선택하세요.");
            }
        }
    }

    long decode(Path source, Format format, Path pcm, int maximumSeconds) throws IOException, InterruptedException {
        var command = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-xerror", "-y",
                "-protocol_whitelist", "file", "-f", format.demuxer, "-i", source.toString(),
                "-map", "0:a:0", "-ar", "16000", "-ac", "1", "-t", Long.toString(maximumSeconds + 1L),
                "-f", "s16le", pcm.toString());
        Process ffmpeg = command.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!ffmpeg.waitFor(5, TimeUnit.MINUTES)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUDIO_DECODE_TIMEOUT", "녹음 파일 확인 시간이 초과됐습니다.");
            }
            long durationMs = Files.size(pcm) / BYTES_PER_MILLISECOND;
            if (ffmpeg.exitValue() != 0 || durationMs == 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AUDIO", "녹음 파일에서 재생 가능한 음성을 읽지 못했습니다.");
            }
            if (durationMs > maximumSeconds * 1000L) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUDIO_LIMIT_EXCEEDED", "녹음이 회의당 전사 시간 한도를 넘었습니다.");
            }
            return durationMs;
        } finally {
            if (ffmpeg.isAlive()) ffmpeg.destroyForcibly();
        }
    }
}
