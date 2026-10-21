package dev.aimeeting.api.provider.deepgram;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Standalone, fail-closed entry point for one explicitly approved real-provider POC. */
public final class DeepgramPocRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long PCM_BYTES_PER_SECOND = 32_000;
    private static final int MAX_EVENTS = 1_000;
    private static final int MAX_WORDS_PER_EVENT = 2_000;
    private static final int MAX_RECORDED_TEXT_CHARS = 1_000_000;
    private static final int MAX_REPORT_BYTES = 4 * 1_024 * 1_024;
    private static final int MAX_EVENT_BYTES = MAX_REPORT_BYTES - 64 * 1_024;
    private static final Pattern PRICE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @FunctionalInterface
    interface Environment {
        String get(String name);
    }

    @FunctionalInterface
    interface Connector {
        CompletableFuture<Transport> connect(String apiKey, DeepgramOptions options, PocLimits limits,
                                             Consumer<DeepgramMessage> messages);
    }

    interface Transport extends AutoCloseable {
        CompletableFuture<Void> sendAudio(byte[] audio);
        CompletableFuture<Void> finish();
        CompletableFuture<DeepgramPocSession.StopReason> completion();
        @Override void close();
    }

    private final Environment environment;
    private final Connector connector;

    public DeepgramPocRunner() {
        this(System::getenv, DeepgramPocRunner::connect);
    }

    DeepgramPocRunner(Environment environment, Connector connector) {
        this.environment = Objects.requireNonNull(environment);
        this.connector = Objects.requireNonNull(connector);
    }

    public static void main(String[] args) {
        int exit = new DeepgramPocRunner().run(args);
        if (exit != 0) System.exit(exit);
    }

    int run(String[] args) {
        OutputReservation reservedOutput = null;
        try {
            if (args.length == 1 && "--help".equals(args[0])) {
                System.out.println(usage());
                return 0;
            }
            Request request = Request.parse(args);
            Preflight preflight = preflight(request);
            reservedOutput = reserveOutput(preflight.output());
            if (!request.execute()) {
                write(reservedOutput, report("dry-run", preflight, List.of(), null));
                return 0;
            }

            String paidEnabled = environment.get("PAID_API_TESTS_ENABLED");
            if (!"true".equals(paidEnabled)) throw new IllegalArgumentException("Paid API execution is disabled");
            String apiKey = environment.get("DEEPGRAM_API_KEY");
            if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Deepgram credential is unavailable");

            EventCollector events = new EventCollector();
            try (Transport transport = connectTransport(apiKey, preflight, events::accept)) {
                stream(preflight, transport);
                transport.finish().get(preflight.finishTimeoutSeconds(), TimeUnit.SECONDS);
                DeepgramPocSession.StopReason reason = transport.completion()
                        .get(preflight.finishTimeoutSeconds(), TimeUnit.SECONDS);
                if (reason != DeepgramPocSession.StopReason.COMPLETED) {
                    throw new IllegalStateException("Deepgram POC did not complete");
                }
            }
            write(reservedOutput, report("completed", preflight, events.snapshot(), "REAL_PROVIDER_MEASUREMENT"));
            return 0;
        } catch (Exception failure) {
            if (reservedOutput != null) {
                try {
                    write(reservedOutput, Map.of(
                            "status", "failed",
                            "goDecision", "NOT_A_GO_DECISION",
                            "actualCostUsd", "UNKNOWN",
                            "error", "POC_EXECUTION_FAILED"));
                } catch (Exception ignored) {
                    // The original failure remains authoritative and no provider detail is exposed.
                }
            }
            System.err.println("Deepgram POC failed: " + safeMessage(failure, reservedOutput == null));
            return 2;
        } finally {
            if (reservedOutput != null) {
                try {
                    reservedOutput.close();
                } catch (IOException ignored) {
                    // A completed bounded write remains authoritative; no path is reopened during cleanup.
                }
            }
        }
    }

    private Transport connectTransport(String apiKey, Preflight preflight,
                                       Consumer<DeepgramMessage> messages) throws Exception {
        CompletableFuture<Transport> opening = connector.connect(
                apiKey, preflight.options(), preflight.limits(), messages);
        try {
            return opening.get(preflight.connectTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            cancelOpening(opening);
            Thread.currentThread().interrupt();
            throw failure;
        } catch (TimeoutException failure) {
            cancelOpening(opening);
            throw failure;
        }
    }

    private static void cancelOpening(CompletableFuture<Transport> opening) {
        opening.whenComplete((late, failure) -> {
            if (late != null) late.close();
        });
        opening.cancel(true);
    }

    private Preflight preflight(Request request) throws IOException {
        Path input = request.input().toAbsolutePath().normalize();
        Path output = request.output().toAbsolutePath().normalize();
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(input)) {
            throw new IllegalArgumentException("Input must be a regular non-link file");
        }
        long bytes = Files.size(input);
        if (bytes <= 0 || (bytes & 1) != 0) throw new IllegalArgumentException("Input must contain even-length PCM16 audio");
        String filename = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".pcm") && !filename.endsWith(".raw")) {
            throw new IllegalArgumentException("Input must use a .pcm or .raw extension");
        }
        long maximumBytes = maximumBytes(request.duration());
        if (bytes > maximumBytes) throw new IllegalArgumentException("Input audio exceeds the explicit duration limit");

        validateOutput(output);
        PocLimits limits = new PocLimits(request.duration(), request.maxUsd(), request.ratePerMinute());
        DeepgramOptions options = new DeepgramOptions(
                request.diarization(), request.keyterms(), DeepgramAudioFormat.pcm16kMono());
        return new Preflight(input, output, bytes, maximumBytes, request.frameMs(), request.priceVersion(), limits, options);
    }

    private static void validateOutput(Path output) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
            throw new IllegalArgumentException("Output already exists");
        }
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("Output parent must be an existing non-link directory");
        }
    }

    private static OutputReservation reserveOutput(Path output) throws IOException {
        FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(output)) {
            channel.close();
            throw new IllegalArgumentException("Output must not be a link");
        }
        return new OutputReservation(channel);
    }

    private static void stream(Preflight preflight, Transport transport) throws Exception {
        byte[] pcm = Files.readAllBytes(preflight.input());
        int frameBytes = Math.toIntExact(PCM_BYTES_PER_SECOND * preflight.frameMs() / 1_000);
        long started = System.nanoTime();
        long sent = 0;
        for (int offset = 0; offset < pcm.length; offset += frameBytes) {
            int length = Math.min(frameBytes, pcm.length - offset);
            if ((length & 1) != 0 || length > preflight.maximumBytes() - sent) {
                throw new IllegalStateException("PCM duration cap reached");
            }
            byte[] frame = java.util.Arrays.copyOfRange(pcm, offset, offset + length);
            transport.sendAudio(frame).get(10, TimeUnit.SECONDS);
            sent += length;
            long target = started + sent * 1_000_000_000L / PCM_BYTES_PER_SECOND;
            long remaining = target - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static Map<String, Object> report(String status, Preflight preflight,
                                               List<Map<String, Object>> events, String classification) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("measurementClassification", classification == null ? "PREFLIGHT_ONLY" : classification);
        report.put("goDecision", "NOT_A_GO_DECISION");
        report.put("audioFormat", "PCM_S16LE_16000_MONO_RAW");
        report.put("syntheticRawPcmApproved", "completed".equals(status));
        report.put("inputBytes", preflight.inputBytes());
        report.put("audioDurationMs", preflight.inputBytes() * 1_000 / PCM_BYTES_PER_SECOND);
        report.put("durationLimitMs", preflight.limits().maxDuration().toMillis());
        report.put("priceVersion", preflight.priceVersion());
        report.put("diarization", preflight.options().diarization());
        report.put("keytermCount", preflight.options().keyterms().size());
        report.put("estimatedCostUsd", estimatedCost(preflight));
        report.put("actualCostUsd", "UNKNOWN");
        report.put("events", events);
        return report;
    }

    private static String estimatedCost(Preflight preflight) {
        return preflight.limits().estimatedUsdPerMinute()
                .multiply(BigDecimal.valueOf(preflight.inputBytes()))
                .divide(BigDecimal.valueOf(PCM_BYTES_PER_SECOND * 60), 6, RoundingMode.UP)
                .toPlainString();
    }

    private static void write(OutputReservation output, Map<String, ?> value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        if (bytes.length > MAX_REPORT_BYTES) throw new IllegalStateException("POC report limit exceeded");
        output.write(bytes);
    }

    private static long maximumBytes(Duration duration) {
        return Math.addExact(Math.multiplyExact(duration.getSeconds(), PCM_BYTES_PER_SECOND),
                duration.getNano() * PCM_BYTES_PER_SECOND / 1_000_000_000L);
    }

    private static String safeMessage(Exception failure, boolean localPreflight) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        if (localPreflight && current instanceof IllegalArgumentException && current.getMessage() != null) {
            return current.getMessage();
        }
        return "execution failed";
    }

    private static String usage() {
        return "Usage: DeepgramPocRunner --input <synthetic.pcm|synthetic.raw> --output <new.json> "
                + "--duration-seconds <0..900> --max-usd <positive> --usd-per-minute <positive> "
                + "--price-version <id> [--frame-ms 20|100] [--diarization] "
                + "[--keyterm <value> ... --keyterms-approved] [--execute --synthetic-approved]\n"
                + "Dry-run is the default and reads no credentials. Execution accepts only headerless signed "
                + "PCM16 little-endian, 16 kHz, mono synthetic audio; .webm and .wav microphone files are unsupported. "
                + "The maximum USD budget, USD-per-minute rate, and price version must be supplied explicitly.";
    }

    private static CompletableFuture<Transport> connect(String apiKey, DeepgramOptions options, PocLimits limits,
                                                        Consumer<DeepgramMessage> messages) {
        CompletableFuture<DeepgramPocSession> source =
                DeepgramPocSession.connect(true, apiKey, options, limits, messages);
        CompletableFuture<Transport> mapped = new CompletableFuture<>();
        source.whenComplete((session, failure) -> {
            if (failure != null) {
                mapped.completeExceptionally(failure);
            } else if (!mapped.complete(new SessionTransport(session))) {
                session.close();
            }
        });
        mapped.whenComplete((transport, failure) -> {
            if (mapped.isCancelled()) source.cancel(true);
        });
        return mapped;
    }

    private static final class OutputReservation implements AutoCloseable {
        private final FileChannel channel;

        private OutputReservation(FileChannel channel) {
            this.channel = channel;
        }

        private void write(byte[] bytes) throws IOException {
            channel.position(0);
            channel.truncate(0);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }

        @Override public void close() throws IOException {
            channel.close();
        }
    }

    private record SessionTransport(DeepgramPocSession session) implements Transport {
        @Override public CompletableFuture<Void> sendAudio(byte[] audio) { return session.sendAudio(audio); }
        @Override public CompletableFuture<Void> finish() { return session.finish(); }
        @Override public CompletableFuture<DeepgramPocSession.StopReason> completion() { return session.completion(); }
        @Override public void close() { session.close(); }
    }

    private record Preflight(Path input, Path output, long inputBytes, long maximumBytes, int frameMs,
                             String priceVersion, PocLimits limits, DeepgramOptions options) {
        long connectTimeoutSeconds() { return Math.max(10, limits.maxDuration().toSeconds() + 5); }
        long finishTimeoutSeconds() { return 10; }
    }

    private record Request(boolean execute, boolean syntheticApproved, boolean diarization,
                           List<String> keyterms, boolean keytermsApproved, Path input, Path output,
                           Duration duration, BigDecimal maxUsd, BigDecimal ratePerMinute,
                           String priceVersion, int frameMs) {
        static Request parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean execute = false;
            boolean syntheticApproved = false;
            boolean diarization = false;
            boolean keytermsApproved = false;
            List<String> keyterms = new ArrayList<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--execute".equals(argument)) { execute = uniqueFlag(values, argument); continue; }
                if ("--synthetic-approved".equals(argument)) { syntheticApproved = uniqueFlag(values, argument); continue; }
                if ("--diarization".equals(argument)) { diarization = uniqueFlag(values, argument); continue; }
                if ("--keyterms-approved".equals(argument)) { keytermsApproved = uniqueFlag(values, argument); continue; }
                if ("--keyterm".equals(argument)) {
                    if (index + 1 >= args.length) throw new IllegalArgumentException("Invalid command line");
                    keyterms.add(args[++index]);
                    continue;
                }
                if (!argument.startsWith("--") || index + 1 >= args.length) {
                    throw new IllegalArgumentException("Invalid command line");
                }
                if (values.putIfAbsent(argument, args[++index]) != null) {
                    throw new IllegalArgumentException("Duplicate command option");
                }
            }
            Path input = Path.of(required(values, "--input"));
            Path output = Path.of(required(values, "--output"));
            Duration duration = parseDuration(required(values, "--duration-seconds"));
            BigDecimal maxUsd = positiveDecimal(required(values, "--max-usd"));
            BigDecimal rate = positiveDecimal(required(values, "--usd-per-minute"));
            String priceVersion = required(values, "--price-version");
            if (!PRICE_VERSION.matcher(priceVersion).matches()) throw new IllegalArgumentException("Invalid price version");
            int frameMs = values.containsKey("--frame-ms") ? parseFrame(values.get("--frame-ms")) : 100;
            values.keySet().removeAll(List.of("--execute", "--synthetic-approved", "--diarization",
                    "--keyterms-approved", "--input", "--output",
                    "--duration-seconds", "--max-usd", "--usd-per-minute", "--price-version", "--frame-ms"));
            if (!values.isEmpty()) throw new IllegalArgumentException("Unknown command option");
            if (execute && !syntheticApproved) throw new IllegalArgumentException("Synthetic audio approval is required");
            if (!keyterms.isEmpty() && !keytermsApproved) throw new IllegalArgumentException("Keyterm approval is required");
            return new Request(execute, syntheticApproved, diarization, List.copyOf(keyterms), keytermsApproved,
                    input, output, duration, maxUsd, rate, priceVersion, frameMs);
        }

        private static boolean uniqueFlag(Map<String, String> values, String name) {
            if (values.putIfAbsent(name, "true") != null) throw new IllegalArgumentException("Duplicate command option");
            return true;
        }

        private static String required(Map<String, String> values, String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required command option");
            return value;
        }

        private static Duration parseDuration(String value) {
            try {
                BigDecimal seconds = new BigDecimal(value);
                if (seconds.signum() <= 0 || seconds.compareTo(BigDecimal.valueOf(900)) > 0) throw new NumberFormatException();
                return Duration.ofNanos(seconds.movePointRight(9).longValueExact());
            } catch (ArithmeticException | NumberFormatException failure) {
                throw new IllegalArgumentException("Duration must be positive and at most 900 seconds");
            }
        }

        private static BigDecimal positiveDecimal(String value) {
            try {
                BigDecimal parsed = new BigDecimal(value);
                if (parsed.signum() <= 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Cost values must be positive decimals");
            }
        }

        private static int parseFrame(String value) {
            if ("20".equals(value)) return 20;
            if ("100".equals(value)) return 100;
            throw new IllegalArgumentException("Frame size must be 20 or 100 milliseconds");
        }
    }

    private static final class EventCollector {
        private final long started = System.nanoTime();
        private final AtomicLong lastMicros = new AtomicLong();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private int recordedTextChars;
        private int recordedEventBytes;

        synchronized void accept(DeepgramMessage message) {
            if (events.size() >= MAX_EVENTS) throw new IllegalStateException("POC event limit exceeded");
            long measured = Math.max(0, (System.nanoTime() - started) / 1_000);
            long receivedMicros = lastMicros.accumulateAndGet(measured, Math::max);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("receivedMicros", receivedMicros);
            if (message instanceof DeepgramMessage.Results result) {
                if (result.words().size() > MAX_WORDS_PER_EVENT) {
                    throw new IllegalStateException("POC word limit exceeded");
                }
                int addedChars = result.transcript().length();
                for (DeepgramMessage.Word word : result.words()) {
                    addedChars = Math.addExact(addedChars, word.text().length());
                }
                if (addedChars > MAX_RECORDED_TEXT_CHARS - recordedTextChars) {
                    throw new IllegalStateException("POC text limit exceeded");
                }
                recordedTextChars += addedChars;
                event.put("type", "results");
                event.put("channelIndex", result.channelIndex());
                event.put("startMs", result.startMs());
                event.put("endMs", result.endMs());
                event.put("transcript", result.transcript());
                event.put("final", result.finalResult());
                event.put("speechFinal", result.speechFinal());
                event.put("fromFinalize", result.fromFinalize());
                event.put("words", result.words().stream().map(word -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("text", word.text());
                    normalized.put("startMs", word.startMs());
                    normalized.put("endMs", word.endMs());
                    normalized.put("speaker", word.speaker());
                    return normalized;
                }).toList());
            } else if (message instanceof DeepgramMessage.Metadata metadata) {
                event.put("type", "metadata");
                event.put("requestId", metadata.requestId());
            } else if (message instanceof DeepgramMessage.Error) {
                event.put("type", "provider-error");
                event.put("code", DeepgramMessageParser.SANITIZED_ERROR_CODE);
            } else {
                event.put("type", "unknown");
            }
            try {
                int bytes = JSON.writeValueAsBytes(event).length;
                if (bytes + 1 > MAX_EVENT_BYTES - recordedEventBytes) {
                    throw new IllegalStateException("POC report limit exceeded");
                }
                recordedEventBytes += bytes + 1;
            } catch (IOException failure) {
                throw new IllegalStateException("POC event serialization failed");
            }
            events.add(event);
        }

        synchronized List<Map<String, Object>> snapshot() {
            return events.stream().map(Map::copyOf).toList();
        }
    }
}
