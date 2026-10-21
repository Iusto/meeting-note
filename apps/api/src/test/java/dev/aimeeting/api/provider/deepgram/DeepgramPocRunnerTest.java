package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeepgramPocRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void dryRunDoesNotReadEnvironmentOrDispatch() throws Exception {
        Path input = pcm("audio.pcm", 640);
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        DeepgramPocRunner runner = new DeepgramPocRunner(name -> {
            environmentReads.incrementAndGet();
            return null;
        }, (key, options, limits, messages) -> {
            dispatches.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("must not dispatch"));
        });

        int exit = runner.run(arguments(input, output, "1"));

        assertThat(exit).isZero();
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
        JsonNode report = JSON.readTree(output.toFile());
        assertThat(report.path("status").asText()).isEqualTo("dry-run");
        assertThat(report.path("goDecision").asText()).isEqualTo("NOT_A_GO_DECISION");
        assertThat(report.path("actualCostUsd").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void refusesExistingOutputBeforeCredentialReadOrDispatch() throws Exception {
        Path input = pcm("audio.pcm", 640);
        Path output = temporary.resolve("existing.json");
        Files.writeString(output, "keep");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        DeepgramPocRunner runner = runner(environmentReads, dispatches, new FakeTransport());

        int exit = runner.run(executeArguments(input, output, "1"));

        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(output)).isEqualTo("keep");
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
    }

    @Test
    void rejectsAudioBeyondDurationBeforeCredentialReadOrDispatch() throws Exception {
        Path input = pcm("long.pcm", 32_002);
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        DeepgramPocRunner runner = runner(environmentReads, dispatches, new FakeTransport());

        int exit = runner.run(executeArguments(input, output, "1"));

        assertThat(exit).isEqualTo(2);
        assertThat(output).doesNotExist();
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
    }

    @Test
    void rejectsContainerizedRecordingBeforeCredentialReadOrDispatch() throws Exception {
        Path input = pcm("microphone.webm", 640);
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        DeepgramPocRunner runner = runner(environmentReads, dispatches, new FakeTransport());

        int exit = runner.run(executeArguments(input, output, "1"));

        assertThat(exit).isEqualTo(2);
        assertThat(output).doesNotExist();
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
    }

    @Test
    void executeUsesBoundedPcmFramesAndWritesSanitizedMeasurement() throws Exception {
        Path input = pcm("audio.pcm", 1_280);
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        FakeTransport transport = new FakeTransport();
        DeepgramPocRunner runner = runner(environmentReads, dispatches, transport);

        int exit = runner.run(executeArguments(input, output, "1"));

        assertThat(exit).isZero();
        assertThat(environmentReads).hasValue(2);
        assertThat(dispatches).hasValue(1);
        assertThat(transport.frames).extracting(frame -> frame.length).containsExactly(1_280);
        JsonNode report = JSON.readTree(output.toFile());
        assertThat(report.path("measurementClassification").asText()).isEqualTo("REAL_PROVIDER_MEASUREMENT");
        assertThat(report.path("goDecision").asText()).isEqualTo("NOT_A_GO_DECISION");
        assertThat(report.path("actualCostUsd").asText()).isEqualTo("UNKNOWN");
        assertThat(report.toString()).doesNotContain("test-key");
        assertThat(report.path("events").get(0).path("type").asText()).isEqualTo("metadata");
        assertThat(report.path("events").get(0).path("requestId").asText()).isEqualTo("request-secret");
        assertThat(report.path("events").get(1).path("words").get(0).path("speaker").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedReportsEvenWhenWordTextsAreEmpty() throws Exception {
        Path input = pcm("bounded.pcm", 640);
        Path output = temporary.resolve("bounded-report.json");
        List<DeepgramMessage.Word> words = java.util.Collections.nCopies(2_000,
                new DeepgramMessage.Word("", 0, 1, null));
        DeepgramPocRunner runner = new DeepgramPocRunner(name -> "true",
                (key, options, limits, messages) -> {
                    for (int i = 0; i < 60; i++) {
                        messages.accept(new DeepgramMessage.Results(List.of(0), 0, 1, "", true,
                                true, false, words));
                    }
                    return CompletableFuture.completedFuture(new FakeTransport());
                });

        assertThat(runner.run(executeArguments(input, output, "1"))).isEqualTo(2);
        assertThat(Files.size(output)).isLessThan(1_024);
        assertThat(JSON.readTree(output.toFile()).path("error").asText()).isEqualTo("POC_EXECUTION_FAILED");
    }

    @Test
    void interruptedConnectCancelsOpeningAndClosesTransportThatArrivesLate() throws Exception {
        Path input = pcm("delayed.pcm", 640);
        Path output = temporary.resolve("delayed.json");
        AtomicInteger cancellations = new AtomicInteger();
        CompletableFuture<DeepgramPocRunner.Transport> delayed = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancellations.incrementAndGet();
                return false;
            }
        };
        CompletableFuture<Void> connecting = new CompletableFuture<>();
        DeepgramPocRunner runner = new DeepgramPocRunner(name -> "true",
                (key, options, limits, messages) -> {
                    connecting.complete(null);
                    return delayed;
                });
        AtomicReference<Integer> exit = new AtomicReference<>();
        Thread run = Thread.startVirtualThread(() -> exit.set(runner.run(executeArguments(input, output, "1"))));
        connecting.get();

        run.interrupt();
        run.join();
        FakeTransport late = new FakeTransport();
        delayed.complete(late);

        assertThat(exit).hasValue(2);
        assertThat(cancellations).hasValue(1);
        assertThat(late.closes).hasValue(1);
    }

    @Test
    void reportWritesStayOnReservedHandleWhenOutputPathIsReplaced() throws Exception {
        Path input = pcm("replacement.pcm", 640);
        Path output = temporary.resolve("report.json");
        Path reservedWitness = temporary.resolve("reserved-witness.json");
        AtomicBoolean replacementSupported = new AtomicBoolean();
        DeepgramPocRunner runner = new DeepgramPocRunner(name -> "true",
                (key, options, limits, messages) -> {
                    try {
                        Files.createLink(reservedWitness, output);
                        Files.delete(output);
                        Files.writeString(output, "replacement");
                        replacementSupported.set(true);
                    } catch (Exception failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    return CompletableFuture.completedFuture(new FakeTransport());
                });

        int exit = runner.run(executeArguments(input, output, "1"));
        Assumptions.assumeTrue(replacementSupported.get(),
                "Platform does not allow replacing an open reserved output path");

        assertThat(exit).isZero();
        assertThat(Files.readString(output)).isEqualTo("replacement");
        assertThat(JSON.readTree(reservedWitness.toFile()).path("status").asText()).isEqualTo("completed");
    }

    private DeepgramPocRunner runner(AtomicInteger environmentReads, AtomicInteger dispatches,
                                     FakeTransport transport) {
        return new DeepgramPocRunner(name -> {
            environmentReads.incrementAndGet();
            return "PAID_API_TESTS_ENABLED".equals(name) ? "true" : "test-key";
        }, (key, options, limits, messages) -> {
            dispatches.incrementAndGet();
            assertThat(key).isEqualTo("test-key");
            assertThat(options.audioFormat()).isSameAs(DeepgramAudioFormat.pcm16kMono());
            assertThat(options.diarization()).isTrue();
            assertThat(options.keyterms()).containsExactly("회의");
            messages.accept(new DeepgramMessage.Metadata("request-secret"));
            messages.accept(new DeepgramMessage.Results(List.of(0, 1), 0, 20, "안건", true, true,
                    false, List.of(new DeepgramMessage.Word("안건", 0, 20, 1))));
            return CompletableFuture.completedFuture(transport);
        });
    }

    private Path pcm(String name, int bytes) throws Exception {
        Path path = temporary.resolve(name);
        Files.write(path, new byte[bytes]);
        return path;
    }

    private String[] arguments(Path input, Path output, String seconds) {
        return new String[] {"--input", input.toString(), "--output", output.toString(),
                "--duration-seconds", seconds, "--max-usd", "1.00", "--usd-per-minute", "0.01",
                "--price-version", "test-2026"};
    }

    private String[] executeArguments(Path input, Path output, String seconds) {
        List<String> values = new ArrayList<>(List.of(arguments(input, output, seconds)));
        values.add("--execute");
        values.add("--synthetic-approved");
        values.add("--diarization");
        values.add("--keyterm");
        values.add("회의");
        values.add("--keyterms-approved");
        return values.toArray(String[]::new);
    }

    private static final class FakeTransport implements DeepgramPocRunner.Transport {
        private final List<byte[]> frames = new ArrayList<>();
        private final AtomicInteger closes = new AtomicInteger();
        @Override public CompletableFuture<Void> sendAudio(byte[] audio) {
            frames.add(audio.clone());
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> finish() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<DeepgramPocSession.StopReason> completion() {
            return CompletableFuture.completedFuture(DeepgramPocSession.StopReason.COMPLETED);
        }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
