package dev.aimeeting.api.storage;

import dev.aimeeting.api.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RecordingStorage {
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final Pattern CHUNK_FILE_NAME = Pattern.compile("chunk-[0-9]{8}\\.bin");
    private static final Pattern TEMP_FILE_NAME = Pattern.compile("\\.(chunk|recording)-[A-Za-z0-9._-]+\\.tmp");
    private static final String QUARANTINE_PREFIX = ".deleting-";
    private final Path root;

    public RecordingStorage(AppProperties properties) throws IOException {
        Files.createDirectories(properties.recordingRoot());
        this.root = properties.recordingRoot().toRealPath();
    }

    public Path resolve(UUID meetingId, String fileName) throws IOException {
        if (fileName == null || !SAFE_FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Unsafe recording file name");
        }
        Path meetingDirectory = root.resolve(meetingId.toString()).normalize();
        if (!meetingDirectory.startsWith(root)) throw new IllegalArgumentException("Recording path escapes its root");
        Files.createDirectories(meetingDirectory);
        Path realMeetingDirectory = meetingDirectory.toRealPath();
        if (!realMeetingDirectory.startsWith(root)) throw new IllegalArgumentException("Recording directory escapes its root");
        Path result = realMeetingDirectory.resolve(fileName).normalize();
        if (!result.startsWith(realMeetingDirectory)) throw new IllegalArgumentException("Recording path escapes its meeting");
        if (Files.exists(result, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(result)) {
            throw new IllegalArgumentException("Symbolic-link recording targets are not allowed");
        }
        return result;
    }

    public Path writeChunk(UUID meetingId, int sequence, byte[] content) throws IOException {
        Path destination = resolve(meetingId, "chunk-%08d.bin".formatted(sequence));
        Path temporary = Files.createTempFile(destination.getParent(), ".chunk-", ".tmp");
        try {
            Files.write(temporary, content);
            moveAtomically(temporary, destination);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public AssembledRecording assemble(UUID meetingId, List<StoredChunk> chunks) throws IOException {
        Path destination = resolve(meetingId, "recording.bin");
        Path temporary = Files.createTempFile(destination.getParent(), ".recording-", ".tmp");
        MessageDigest digest = sha256Digest();
        long bytes = 0;
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                for (StoredChunk chunk : chunks) {
                    Path safeChunk = requireStoredPath(meetingId, chunk.path());
                    MessageDigest chunkDigest = sha256Digest();
                    long chunkBytes = 0;
                    try (InputStream input = Files.newInputStream(safeChunk)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            chunkDigest.update(buffer, 0, read);
                            bytes += read;
                            chunkBytes += read;
                        }
                    }
                    String actualChunkSha = HexFormat.of().formatHex(chunkDigest.digest());
                    if (chunkBytes != chunk.bytes() || !actualChunkSha.equals(chunk.sha256())) {
                        throw new RecordingIntegrityException("Stored recording chunk failed its integrity check");
                    }
                }
            }
            if (webmParts(meetingId, chunks) > 1) {
                remuxWebm(meetingId, chunks, temporary);
                bytes = Files.size(temporary);
                digest = sha256Digest();
                try (InputStream input = Files.newInputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                }
            }
            moveAtomically(temporary, destination);
            return new AssembledRecording(destination, bytes, HexFormat.of().formatHex(digest.digest()));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean startsWebm(UUID meetingId, StoredChunk chunk) throws IOException {
        try (InputStream input = Files.newInputStream(requireStoredPath(meetingId, chunk.path()))) {
            return java.util.Arrays.equals(input.readNBytes(4), new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3});
        }
    }

    private int webmParts(UUID meetingId, List<StoredChunk> chunks) throws IOException {
        int parts = 0;
        for (StoredChunk chunk : chunks) if (startsWebm(meetingId, chunk)) parts++;
        return parts;
    }

    // Independent MediaRecorder sessions each contain a WebM header and timestamps starting at zero.
    // Remux their verified bytes rather than concatenating containers into an unplayable file.
    private void remuxWebm(UUID meetingId, List<StoredChunk> chunks, Path destination) throws IOException {
        Path directory = destination.getParent();
        java.util.ArrayList<Path> temporaryFiles = new java.util.ArrayList<>();
        java.util.ArrayList<Path> parts = new java.util.ArrayList<>();
        OutputStream output = null;
        try {
            for (StoredChunk chunk : chunks) {
                if (output == null || startsWebm(meetingId, chunk)) {
                    if (output != null) output.close();
                    Path part = Files.createTempFile(directory, ".recording-part-", ".tmp");
                    temporaryFiles.add(part);
                    parts.add(part);
                    output = Files.newOutputStream(part);
                }
                try (InputStream input = Files.newInputStream(requireStoredPath(meetingId, chunk.path()))) {
                    input.transferTo(output);
                }
            }
            if (output != null) { output.close(); output = null; }
            Path manifest = Files.createTempFile(directory, ".recording-list-", ".tmp");
            temporaryFiles.add(manifest);
            Files.writeString(manifest, parts.stream().map(part -> "file '" + part.getFileName() + "'\n")
                    .collect(java.util.stream.Collectors.joining()));
            Path merged = Files.createTempFile(directory, ".recording-merged-", ".tmp");
            temporaryFiles.add(merged);
            Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-y",
                    "-f", "concat", "-safe", "0", "-protocol_whitelist", "file", "-i", manifest.getFileName().toString(),
                    "-map", "0:a:0", "-c", "copy", "-f", "webm", merged.getFileName().toString())
                    .directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Recording remux timed out; source chunks are retained");
                }
                if (process.exitValue() != 0 || Files.size(merged) == 0) {
                    throw new IOException("Recording remux failed; source chunks are retained");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Recording remux interrupted", interrupted);
            }
            Files.move(merged, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (output != null) output.close();
            for (Path temporary : temporaryFiles) Files.deleteIfExists(temporary);
        }
    }

    public Path requireStoredPath(UUID meetingId, Path stored) throws IOException {
        Path safe = resolve(meetingId, stored.getFileName().toString());
        if (!safe.equals(stored.toAbsolutePath().normalize()) || !Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recording file is missing or unsafe");
        }
        return safe;
    }

    public Quarantine quarantine(UUID meetingId) throws IOException {
        Path source = meetingDirectory(meetingId);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return null;
        validateFlatDirectory(source, meetingId.toString());
        Path quarantine = quarantineDirectory(meetingId);
        if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recording deletion quarantine already exists");
        }
        moveSameVolume(source, quarantine);
        return new Quarantine(meetingId, quarantine);
    }

    public void restore(Quarantine quarantine) throws IOException {
        Path source = requireQuarantine(quarantine);
        validateFlatDirectory(source, QUARANTINE_PREFIX + quarantine.meetingId());
        Path destination = meetingDirectory(quarantine.meetingId());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recording restore destination already exists");
        }
        moveSameVolume(source, destination);
    }

    public void purge(Quarantine quarantine) throws IOException {
        Path directory = requireQuarantine(quarantine);
        List<Path> files = validateFlatDirectory(directory, QUARANTINE_PREFIX + quarantine.meetingId());
        for (Path file : files) Files.delete(file);
        Files.delete(directory);
    }

    public List<Quarantine> quarantines() throws IOException {
        try (var entries = Files.newDirectoryStream(root, QUARANTINE_PREFIX + "*")) {
            java.util.ArrayList<Quarantine> result = new java.util.ArrayList<>();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                UUID meetingId;
                try {
                    meetingId = UUID.fromString(name.substring(QUARANTINE_PREFIX.length()));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Unexpected recording deletion quarantine name", exception);
                }
                Quarantine quarantine = new Quarantine(meetingId, entry.toAbsolutePath().normalize());
                validateFlatDirectory(requireQuarantine(quarantine), name);
                result.add(quarantine);
            }
            return List.copyOf(result);
        }
    }

    private List<Path> validateFlatDirectory(Path directory, String expectedName) throws IOException {
        Path expected = root.resolve(expectedName).toAbsolutePath().normalize();
        if (!directory.toAbsolutePath().normalize().equals(expected) || !expected.getParent().equals(root)) {
            throw new IOException("Recording directory is outside its canonical root");
        }
        BasicFileAttributes directoryAttributes = Files.readAttributes(expected, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!directoryAttributes.isDirectory() || Files.isSymbolicLink(expected)
                || !expected.toRealPath().equals(expected)) {
            throw new IOException("Recording directory is a link or unsupported filesystem entry");
        }
        java.util.ArrayList<Path> files = new java.util.ArrayList<>();
        try (var entries = Files.newDirectoryStream(expected)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || Files.isSymbolicLink(entry) || !allowedDeletionName(name)) {
                    throw new IOException("Recording directory contains an unexpected entry");
                }
                files.add(entry);
            }
        }
        return List.copyOf(files);
    }

    private boolean allowedDeletionName(String name) {
        return "recording.bin".equals(name) || CHUNK_FILE_NAME.matcher(name).matches()
                || TEMP_FILE_NAME.matcher(name).matches();
    }

    private Path requireQuarantine(Quarantine quarantine) throws IOException {
        Path expected = quarantineDirectory(quarantine.meetingId());
        if (!expected.equals(quarantine.path().toAbsolutePath().normalize())) {
            throw new IOException("Recording quarantine path is invalid");
        }
        return expected;
    }

    private Path meetingDirectory(UUID meetingId) {
        return root.resolve(meetingId.toString()).toAbsolutePath().normalize();
    }

    private Path quarantineDirectory(UUID meetingId) {
        return root.resolve(QUARANTINE_PREFIX + meetingId).toAbsolutePath().normalize();
    }

    private void moveSameVolume(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record AssembledRecording(Path path, long bytes, String sha256) {}
    public record StoredChunk(Path path, long bytes, String sha256) {}
    public record Quarantine(UUID meetingId, Path path) {}
    public static final class RecordingIntegrityException extends IOException {
        public RecordingIntegrityException(String message) { super(message); }
    }
}
