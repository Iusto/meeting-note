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
}
