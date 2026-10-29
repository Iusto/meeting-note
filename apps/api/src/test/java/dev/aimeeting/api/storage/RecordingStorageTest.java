package dev.aimeeting.api.storage;

import static org.assertj.core.api.Assertions.*;

import dev.aimeeting.api.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingStorageTest {
    @TempDir Path temp;

    @Test
    void acceptsSimpleNamesAndRejectsTraversal() throws Exception {
        RecordingStorage storage = new RecordingStorage(new AppProperties("a-valid-owner-token-000000000000", List.of(), temp, false, 100));
        UUID meetingId = UUID.randomUUID();
        assertThat(storage.resolve(meetingId, "chunk-0001.webm").normalize().startsWith(temp.toRealPath())).isTrue();
        assertThatThrownBy(() -> storage.resolve(meetingId, "../outside.webm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resolve(meetingId, "C:\\outside.webm"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExistingSymbolicLinkTargetsWhenSupported() throws Exception {
        RecordingStorage storage = new RecordingStorage(new AppProperties("a-valid-owner-token-000000000000", List.of(), temp, false, 100));
        UUID meetingId = UUID.randomUUID();
        Path candidate = storage.resolve(meetingId, "linked.webm");
        Path outside = Files.createFile(temp.resolve("outside.webm"));
        try {
            Files.createSymbolicLink(candidate, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    () -> "Symbolic links are unavailable in this environment: " + exception.getClass().getSimpleName());
        }
        assertThatThrownBy(() -> storage.resolve(meetingId, "linked.webm"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Symbolic-link");
    }

    @Test
    void quarantineValidatesEveryFlatEntryBeforeMovingAndPurgesKnownFiles() throws Exception {
        RecordingStorage storage = new RecordingStorage(new AppProperties("a-valid-owner-token-000000000000", List.of(), temp, false, 100));
        UUID safeMeeting = UUID.randomUUID();
        Files.write(storage.resolve(safeMeeting, "chunk-00000000.bin"), new byte[] {1});
        Files.write(storage.resolve(safeMeeting, "recording.bin"), new byte[] {2});
        Path safeDirectory = storage.resolve(safeMeeting, "recording.bin").getParent();
        Files.write(safeDirectory.resolve(".chunk-12345.tmp"), new byte[] {3});

        RecordingStorage.Quarantine quarantine = storage.quarantine(safeMeeting);
        assertThat(safeDirectory).doesNotExist();
        assertThat(quarantine.path()).exists();
        storage.purge(quarantine);
        assertThat(quarantine.path()).doesNotExist();

        UUID unsafeMeeting = UUID.randomUUID();
        Path valid = storage.resolve(unsafeMeeting, "chunk-00000000.bin");
        Files.write(valid, new byte[] {1});
        Files.write(storage.resolve(unsafeMeeting, "unexpected.txt"), new byte[] {2});
        Path unsafeDirectory = valid.getParent();
        assertThatThrownBy(() -> storage.quarantine(unsafeMeeting)).isInstanceOf(java.io.IOException.class);
        assertThat(unsafeDirectory).exists();
        assertThat(temp.resolve(".deleting-" + unsafeMeeting)).doesNotExist();
    }

    @Test
    void quarantineRejectsSubdirectoriesAndFileLinksWhenSupported() throws Exception {
        RecordingStorage storage = new RecordingStorage(new AppProperties("a-valid-owner-token-000000000000", List.of(), temp, false, 100));
        UUID subdirectoryMeeting = UUID.randomUUID();
        Path directory = storage.resolve(subdirectoryMeeting, "recording.bin").getParent();
        Files.createDirectory(directory.resolve("nested"));
        assertThatThrownBy(() -> storage.quarantine(subdirectoryMeeting)).isInstanceOf(java.io.IOException.class);

        UUID linkedMeeting = UUID.randomUUID();
        Path linked = storage.resolve(linkedMeeting, "chunk-00000000.bin");
        Path outside = Files.createFile(temp.resolve("outside-linked.bin"));
        try {
            Files.createSymbolicLink(linked, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    () -> "Symbolic links are unavailable in this environment: " + exception.getClass().getSimpleName());
        }
        assertThatThrownBy(() -> storage.quarantine(linkedMeeting)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void restoreRefusesToOverwriteAnExistingMeetingDirectory() throws Exception {
        RecordingStorage storage = new RecordingStorage(new AppProperties("a-valid-owner-token-000000000000", List.of(), temp, false, 100));
        UUID meetingId = UUID.randomUUID();
        Files.write(storage.resolve(meetingId, "chunk-00000000.bin"), new byte[] {1});
        RecordingStorage.Quarantine quarantine = storage.quarantine(meetingId);
        Files.write(storage.resolve(meetingId, "recording.bin"), new byte[] {2});

        assertThatThrownBy(() -> storage.restore(quarantine)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("already exists");
        assertThat(quarantine.path()).exists();
    }
    @Test
    void failedResumedWebmAssemblyPreservesSourceChunksAndDoesNotPublishAudio() throws Exception {
        RecordingStorage storage = new RecordingStorage(new AppProperties("a-valid-owner-token-000000000000", List.of(), temp, false, 100));
        UUID meetingId = UUID.randomUUID();
        byte[] invalidWebm = new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0};
        var chunks = new java.util.ArrayList<RecordingStorage.StoredChunk>();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(invalidWebm));
        for (int sequence = 0; sequence < 2; sequence++) {
            Path path = storage.writeChunk(meetingId, sequence, invalidWebm);
            chunks.add(new RecordingStorage.StoredChunk(path, invalidWebm.length, hash));
        }
        assertThatThrownBy(() -> storage.assemble(meetingId, chunks)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(storage.resolve(meetingId, "recording.bin"))).isFalse();
        for (var chunk : chunks) assertThat(Files.readAllBytes(chunk.path())).isEqualTo(invalidWebm);
        try (var files = Files.list(chunks.getFirst().path().getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("chunk-00000000.bin", "chunk-00000001.bin");
        }
    }

    @Test
    void resumedWebmContainsBothIndependentRecorderSessionsWhenFfmpegIsInstalled() throws Exception {
        try {
            Process check = new ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            org.junit.jupiter.api.Assumptions.assumeTrue(check.waitFor() == 0, "FFmpeg must be installed for media remux validation");
        } catch (java.io.IOException missing) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "FFmpeg is unavailable; media remux validation requires the Docker image");
        }
        Path tone = temp.resolve("tone.webm");
        Process generate = new ProcessBuilder("ffmpeg", "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=1", "-c:a", "libopus", tone.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(generate.waitFor()).isZero();
        byte[] content = Files.readAllBytes(tone);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        RecordingStorage storage = new RecordingStorage(new AppProperties("a-valid-owner-token-000000000000", List.of(), temp, false, 100));
        UUID meetingId = UUID.randomUUID();
        var chunks = new java.util.ArrayList<RecordingStorage.StoredChunk>();
        for (int sequence = 0; sequence < 2; sequence++) chunks.add(new RecordingStorage.StoredChunk(
                storage.writeChunk(meetingId, sequence, content), content.length, hash));
        var result = storage.assemble(meetingId, chunks);
        Path decoded = temp.resolve("decoded.pcm");
        Process decode = new ProcessBuilder("ffmpeg", "-nostdin", "-y", "-v", "error", "-i", result.path().toString(),
                "-f", "s16le", "-ar", "16000", "-ac", "1", decoded.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(decode.waitFor()).isZero();
        assertThat(Files.size(decoded)).isGreaterThan(60_000); // Two seconds at 16 kHz, rather than the first session only.
        assertThat(result.bytes()).isEqualTo(Files.size(result.path()));
    }

}
