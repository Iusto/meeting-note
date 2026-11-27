package dev.aimeeting.api.recording;

import dev.aimeeting.api.config.AiConfiguration;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.openai.MeetingAiApplicationService;
import dev.aimeeting.api.provider.whisper.WhisperClient;
import dev.aimeeting.api.storage.RecordingStorage;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.web.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecordingImportService {
    static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(RecordingImportService.class);
    private static final String JOB_QUERY = "SELECT meeting_id, file_format, state, error_message FROM recording_imports";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MeetingRepository meetings;
    private final RecordingRepository recordings;
    private final RecordingStorage storage;
    private final ImportedAudio audio;
    private final WhisperClient whisper;
    private final MeetingAiApplicationService ai;
    private final SummaryService summaries;
    private final AiConfiguration config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("recording-import").factory());
    private final AtomicBoolean working = new AtomicBoolean();
    private volatile boolean ready;

    public RecordingImportService(JdbcTemplate jdbc, TransactionTemplate transactions, MeetingRepository meetings,
                                  RecordingRepository recordings, RecordingStorage storage, ImportedAudio audio,
                                  WhisperClient whisper, MeetingAiApplicationService ai, SummaryService summaries,
                                  AiConfiguration config) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.meetings = meetings;
        this.recordings = recordings;
        this.storage = storage;
        this.audio = audio;
        this.whisper = whisper;
        this.ai = ai;
        this.summaries = summaries;
        this.config = config;
    }

    public Meeting submit(String title, UUID topicId, String extension, InputStream input, long contentLength) throws IOException {
        requireWhisper();
        config.requireLlm();
        var format = ImportedAudio.Format.parse(extension);
        if (title.isBlank() || title.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TITLE", "회의 제목은 1~200자로 입력하세요.");
        }
        if (contentLength > MAX_UPLOAD_BYTES) throw tooLarge();
        Path temporary = Files.createTempFile("meeting-upload-", ".audio");
        try {
            MessageDigest digest = sha256();
            long size = copyUpload(input, temporary, digest);
            String hash = HexFormat.of().formatHex(digest.digest());
            UUID[] meetingId = new UUID[1];
            try {
                return transactions.execute(status -> {
                    Meeting meeting = meetings.createFromRecording(title, topicId);
                    meetingId[0] = meeting.id();
                    try {
                        Files.copy(temporary, storage.resolve(meeting.id(), "recording.bin"));
                    } catch (IOException failed) {
                        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORDING_STORAGE_FAILED", "원본 녹음을 저장하지 못했습니다.");
                    }
                    var recording = recordings.ensureAndLock(meeting.id());
                    recordings.complete(recording.id(), 0, size, 0, hash, format.mimeType);
                    jdbc.update("INSERT INTO recording_imports(meeting_id,file_format,state) VALUES (?,?,?)",
                            meeting.id(), format.name(), "QUEUED");
                    return meeting;
                });
            } catch (RuntimeException failed) {
                if (meetingId[0] != null) discardFailedUpload(meetingId[0]);
                throw failed;
            }
        } finally {
            removeTemporary(temporary);
        }
    }

    public Status status(UUID meetingId) {
        Job job = find(meetingId);
        if ("SUMMARY_FAILED".equals(job.state()) && summaries.latestOrNull(meetingId) != null) {
            return new Status("COMPLETED", null);
        }
        return new Status(job.state(), job.error());
    }

    public Status retry(UUID meetingId) {
        config.requireLlm();
        return transactions.execute(status -> {
            meetings.lock(meetingId);
            Job job = find(meetingId);
            String next = switch (job.state()) {
                case "TRANSCRIPTION_FAILED" -> {
                    requireWhisper();
                    yield "QUEUED";
                }
                case "SUMMARY_FAILED" -> "SUMMARY_QUEUED";
                default -> throw new ApiException(HttpStatus.CONFLICT, "RECORDING_IMPORT_NOT_RETRYABLE", "실패한 작업만 다시 요청할 수 있습니다.");
            };
            update(meetingId, next, null);
            return new Status(next, null);
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (Job job : jdbc.query(JOB_QUERY + " WHERE state IN ('TRANSCRIBING','SUMMARIZING')", this::map)) {
            if ("TRANSCRIBING".equals(job.state())) {
                update(job.meetingId(), "TRANSCRIPTION_FAILED", "서버 재시작으로 전사가 중단됐습니다.");
            } else if (summaries.latestOrNull(job.meetingId()) != null) {
                update(job.meetingId(), "COMPLETED", null);
            } else {
                update(job.meetingId(), "SUMMARY_FAILED", "서버 재시작으로 요약이 중단됐습니다.");
            }
        }
        ready = true;
    }

    @Scheduled(fixedDelay = 2000)
    public void schedule() {
        if (ready && working.compareAndSet(false, true)) {
            worker.execute(() -> {
                try { runNext(); }
                catch (RuntimeException failure) { log.error("Recording import worker failed: {}", failure.getClass().getSimpleName()); }
                finally { working.set(false); }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        ready = false;
        worker.shutdownNow();
    }

    void runNext() {
        var queued = jdbc.query(JOB_QUERY
                + " WHERE state IN ('QUEUED','SUMMARY_QUEUED') ORDER BY created_at, meeting_id LIMIT 1", this::map);
        if (queued.isEmpty()) return;
        Job job = queued.getFirst();
        boolean transcribe = "QUEUED".equals(job.state());
        try {
            boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> {
                meetings.lock(job.meetingId());
                return jdbc.update("""
                        UPDATE recording_imports SET state=?, error_message=NULL
                         WHERE meeting_id=? AND state=?
                        """, transcribe ? "TRANSCRIBING" : "SUMMARIZING", job.meetingId(), job.state()) == 1;
            }));
            if (!claimed) return;
        } catch (ApiException deleted) {
            if ("MEETING_NOT_FOUND".equals(deleted.code())) return;
            throw deleted;
        }
        if (transcribe) transcribe(job);
        else summarize(job.meetingId());
    }

    private void transcribe(Job job) {
        UUID meetingId = job.meetingId();
        Path temporary = null;
        try {
            requireWhisper();
            var source = storage.requireStoredPath(meetingId, storage.resolve(meetingId, "recording.bin"));
            temporary = Files.createTempFile("meeting-import-", ".pcm");
            long durationMs = audio.decode(source, job.format(), temporary, config.transcriptionMaximumSeconds());
            recordings.setImportDuration(meetingId, durationMs);
            var segments = whisper.transcribeFile(temporary, durationMs);
            if (segments.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RECORDING_NO_SPEECH", "녹음에서 전사할 발언을 찾지 못했습니다.");
            }
            transactions.executeWithoutResult(status -> {
                meetings.insertImportedTranscript(meetingId, segments);
                update(meetingId, "SUMMARY_QUEUED", null);
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | IOException failure) {
            update(meetingId, "TRANSCRIPTION_FAILED", safeMessage(failure, "녹음 파일 전사를 완료하지 못했습니다."));
            log.warn("Recording transcription failed for {}: {}", meetingId, failure.getClass().getSimpleName());
        } finally {
            if (temporary != null) removeTemporary(temporary);
        }
    }

    private void summarize(UUID meetingId) {
        try {
            if (summaries.latestOrNull(meetingId) == null) ai.generateSummary(meetingId).get();
            update(meetingId, "COMPLETED", null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | ExecutionException failure) {
            Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
            if (cause instanceof ApiException busy && "AI_BUSY".equals(busy.code())) {
                update(meetingId, "SUMMARY_QUEUED", null);
                return;
            }
            update(meetingId, "SUMMARY_FAILED", safeMessage(cause, "요약 생성에 실패했습니다. 저장된 전사로 다시 요약할 수 있습니다."));
            log.warn("Recording summary failed for {}: {}", meetingId, cause.getClass().getSimpleName());
        }
    }

    private void requireWhisper() {
        if (!config.whisperEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "WHISPER_NOT_CONFIGURED", "녹음 파일 전사에는 로컬 Whisper가 필요합니다.");
        }
    }

    private Job find(UUID meetingId) {
        var rows = jdbc.query(JOB_QUERY + " WHERE meeting_id=?", this::map, meetingId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RECORDING_IMPORT_NOT_FOUND", "업로드 작업을 찾지 못했습니다.");
        return rows.getFirst();
    }

    private Job map(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Job(row.getObject("meeting_id", UUID.class), ImportedAudio.Format.valueOf(row.getString("file_format")),
                row.getString("state"), row.getString("error_message"));
    }

    private void update(UUID meetingId, String state, String error) {
        jdbc.update("UPDATE recording_imports SET state=?, error_message=? WHERE meeting_id=?", state, error, meetingId);
    }

    private long copyUpload(InputStream input, Path temporary, MessageDigest digest) throws IOException {
        long size = 0;
        try (var source = new DigestInputStream(input, digest); var destination = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = source.read(buffer)) != -1) {
                size += length;
                if (size > MAX_UPLOAD_BYTES) throw tooLarge();
                destination.write(buffer, 0, length);
            }
        }
        if (size == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_RECORDING", "녹음 파일이 비어 있습니다.");
        return size;
    }

    private void discardFailedUpload(UUID meetingId) {
        try {
            var quarantine = storage.quarantine(meetingId);
            if (quarantine != null) storage.purge(quarantine);
        } catch (IOException failure) {
            log.warn("Failed upload cleanup remains pending for {}", meetingId);
        }
    }

    private void removeTemporary(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException failure) { log.warn("Recording import temporary file cleanup failed"); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "RECORDING_TOO_LARGE", "녹음 파일은 256 MiB까지 업로드할 수 있습니다.");
    }

    private static String safeMessage(Throwable failure, String fallback) {
        return failure instanceof ApiException known ? known.getMessage() : fallback;
    }

    public record Status(String state, String error) {}
    private record Job(UUID meetingId, ImportedAudio.Format format, String state, String error) {}
}
