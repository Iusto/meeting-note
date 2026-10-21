package dev.aimeeting.api.provider.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.provider.SummaryProvider;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Standalone, fail-closed entry point for one explicitly approved synthetic OpenAI POC. */
public final class OpenAiPocRunner {
    private static final int MAX_INPUT_BYTES = 256 * 1024;
    private static final int MAX_REPORT_BYTES = 512 * 1024;
    private static final int INPUT_FRAMING_RESERVE = 4_096;
    private static final int MAX_INPUT_RESERVATION = 200_000;
    private static final Pattern PRICE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]{0,19})(?:\\.[0-9]{1,12})?");
    private static final Set<String> SAFE_MESSAGES = Set.of(
            "Invalid command line", "Duplicate command option", "Missing required command option",
            "Unknown command option", "Invalid price version", "Cost values must be positive decimals",
            "Output token cap must be positive and at most 4096",
            "Timeout must be positive and at most 120 seconds", "Synthetic transcript approval is required",
            "Input must be a regular non-link file", "Input snapshot exceeds the POC limit",
            "Invalid synthetic transcript snapshot", "Output already exists",
            "Output parent must be an existing non-link directory", "Output must not be a link",
            "OpenAI request exceeds the input reservation", "Invalid OpenAI request input",
            "Invalid OpenAI POC request", "Projected request cost exceeds the POC budget",
            "Invalid OpenAI POC limits", "Paid API execution is disabled", "OpenAI credential is unavailable");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @FunctionalInterface
    interface Environment {
        String get(String name);
    }

    @FunctionalInterface
    interface Transport {
        CompletableFuture<OpenAiPocClient.Response> request(String apiKey, ObjectNode payload,
                                                            OpenAiPocLimits limits);
    }

    private final Environment environment;
    private final Transport transport;

    public OpenAiPocRunner() {
        this(System::getenv, (apiKey, payload, limits) -> OpenAiPocClient.request(true, apiKey, payload, limits));
    }

    OpenAiPocRunner(Environment environment, Transport transport) {
        this.environment = Objects.requireNonNull(environment);
        this.transport = Objects.requireNonNull(transport);
    }

    public static void main(String[] args) {
        int exit = new OpenAiPocRunner().run(args);
        if (exit != 0) System.exit(exit);
    }

    int run(String[] args) {
        ReservedOutput reservedOutput = null;
        try {
            Request request = Request.parse(args);
            Preflight preflight = preflight(request);
            reservedOutput = reserveOutput(preflight.output());
            if (!request.execute()) {
                write(reservedOutput, report("dry-run", preflight, null, null));
                return 0;
            }

            if (!"true".equals(environment.get("PAID_API_TESTS_ENABLED"))) {
                throw new IllegalArgumentException("Paid API execution is disabled");
            }
            String apiKey = environment.get("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("OpenAI credential is unavailable");
            }

            CompletableFuture<OpenAiPocClient.Response> exchange =
                    transport.request(apiKey, preflight.payload(), preflight.limits());
            OpenAiPocClient.Response response;
            try {
                response = exchange.get(preflight.limits().timeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                exchange.cancel(true);
                throw new IllegalStateException("OpenAI request timed out");
            } catch (InterruptedException interrupted) {
                exchange.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenAI request interrupted");
            }
            Completed completed = validateCompleted(response, preflight);
            write(reservedOutput, report("completed", preflight, response, completed));
            return 0;
        } catch (Exception failure) {
            if (reservedOutput != null) {
                try {
                    write(reservedOutput, Map.of(
                            "status", "failed",
                            "measurementClassification", "NO_VALID_MEASUREMENT",
                            "goDecision", "NOT_A_GO_DECISION",
                            "actualCostUsd", "UNKNOWN",
                            "error", "POC_EXECUTION_FAILED"));
                } catch (Exception ignored) {
                    // The original failure remains authoritative and no provider detail is exposed.
                }
            }
            System.err.println("OpenAI POC failed: " + safeMessage(failure));
            return 2;
        } finally {
            if (reservedOutput != null) reservedOutput.close();
        }
    }

    private static Preflight preflight(Request request) throws IOException {
        Path input = request.input().toAbsolutePath().normalize();
        Path output = request.output().toAbsolutePath().normalize();
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(input)) {
            throw new IllegalArgumentException("Input must be a regular non-link file");
        }
        long inputBytes = Files.size(input);
        if (inputBytes <= 0 || inputBytes > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Input snapshot exceeds the POC limit");
        }
        validateOutput(output);

        List<TranscriptSegment> transcript = readTranscript(input);
        ObjectNode payload = MeetingAiPayloads.summaryRequest(transcript, request.outputTokenCap());
        int requestBytes = JSON.writeValueAsBytes(payload).length;
        int inputReservation = Math.addExact(requestBytes, INPUT_FRAMING_RESERVE);
        if (inputReservation > MAX_INPUT_RESERVATION) {
            throw new IllegalArgumentException("OpenAI request exceeds the input reservation");
        }
        OpenAiPocLimits limits = new OpenAiPocLimits(request.timeout(), inputReservation,
                request.outputTokenCap(), request.inputRate(), request.outputRate(), request.maxUsd());
        OpenAiPocClient.validateRequest(payload, limits);
        return new Preflight(input, output, inputBytes, transcript, payload, request.priceVersion(), limits);
    }

    private static List<TranscriptSegment> readTranscript(Path input) throws IOException {
        JsonNode root = JSON.readTree(Files.readAllBytes(input));
        if (!(root instanceof ObjectNode object)
                || !fields(object).equals(Set.of("schemaVersion", "measurementOrigin", "segments"))
                || !object.path("schemaVersion").isIntegralNumber()
                || object.path("schemaVersion").intValue() != 1
                || !"synthetic/offline".equals(object.path("measurementOrigin").asText())
                || !object.path("segments").isArray()
                || object.path("segments").isEmpty()
                || object.path("segments").size() > 100) {
            throw new IllegalArgumentException("Invalid synthetic transcript snapshot");
        }
        List<TranscriptSegment> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode value : object.path("segments")) {
            if (!(value instanceof ObjectNode segment)
                    || !fields(segment).equals(Set.of("id", "revision", "userLocked", "displayText"))
                    || !segment.path("id").isTextual()
                    || !segment.path("revision").isIntegralNumber()
                    || !segment.path("revision").canConvertToInt()
                    || segment.path("revision").intValue() < 0
                    || !segment.path("userLocked").isBoolean()
                    || !segment.path("displayText").isTextual()) {
                throw new IllegalArgumentException("Invalid synthetic transcript snapshot");
            }
            UUID id;
            try {
                id = UUID.fromString(segment.path("id").textValue());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid synthetic transcript snapshot");
            }
            if (!ids.add(id)) throw new IllegalArgumentException("Invalid synthetic transcript snapshot");
            String text = segment.path("displayText").textValue();
            int revision = segment.path("revision").intValue();
            result.add(new TranscriptSegment(id, id, "synthetic", result.size(), 0, 0, text, text,
                    "FINAL", null, "UNKNOWN", revision, segment.path("userLocked").booleanValue()));
        }
        return List.copyOf(result);
    }

    private static Set<String> fields(ObjectNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Completed validateCompleted(OpenAiPocClient.Response response, Preflight preflight) {
        if (response == null || response.status() != OpenAiPocClient.Status.COMPLETED
                || response.responseId() == null || response.responseId().isBlank()
                || response.responseId().length() > 256 || response.outputJson() == null) {
            throw new IllegalStateException("OpenAI POC did not complete");
        }
        OpenAiPocClient.TokenUsage usage = response.usage();
        if (usage == null || usage.inputTokens() < 0 || usage.outputTokens() < 0
                || usage.totalTokens() < 0 || usage.inputTokens() > preflight.limits().maxInputTokens()
                || usage.outputTokens() > preflight.limits().maxOutputTokens()
                || safeSum(usage.inputTokens(), usage.outputTokens()) != usage.totalTokens()) {
            throw new IllegalStateException("Invalid OpenAI usage");
        }
        SummaryProvider.Summary summary = MeetingAiPayloads.parseSummary(response.outputJson(), preflight.transcript());
        JsonNode structured;
        try {
            structured = JSON.readTree(response.outputJson());
        } catch (IOException invalid) {
            throw new IllegalStateException("Invalid OpenAI response");
        }
        if (summary == null || structured == null) throw new IllegalStateException("Invalid OpenAI response");
        return new Completed(structured, preflight.limits().estimatedUsd(usage.inputTokens(), usage.outputTokens()));
    }

    private static long safeSum(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private static Map<String, Object> report(String status, Preflight preflight,
                                               OpenAiPocClient.Response response, Completed completed) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("measurementClassification", completed == null ? "PREFLIGHT_ONLY" : "REAL_PROVIDER_MEASUREMENT");
        report.put("goDecision", "NOT_A_GO_DECISION");
        report.put("model", "gpt-6.1-sol");
        report.put("priceVersion", preflight.priceVersion());
        report.put("inputBytes", preflight.inputBytes());
        report.put("inputTokenReservation", preflight.limits().maxInputTokens());
        report.put("outputTokenCap", preflight.limits().maxOutputTokens());
        report.put("maximumEstimatedCostUsd", preflight.limits().maximumEstimatedUsd().toPlainString());
        report.put("estimatedCostUsd", completed == null ? preflight.limits().maximumEstimatedUsd().toPlainString()
                : completed.estimatedCost().toPlainString());
        report.put("actualCostUsd", "UNKNOWN");
        report.put("requestId", response == null ? null : response.responseId());
        report.put("usage", response == null ? null : response.usage());
        report.put("structuredOutput", completed == null ? null : completed.structuredOutput());
        return report;
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

    private static ReservedOutput reserveOutput(Path output) throws IOException {
        FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(output)) {
            channel.close();
            throw new IllegalArgumentException("Output must not be a link");
        }
        return new ReservedOutput(channel);
    }

    private static void write(ReservedOutput output, Map<String, ?> value) throws IOException {
        output.write(reportBytes(value));
    }

    // Kept as a narrow serialization regression seam; production retains the channel returned by reserveOutput.
    private static void write(Path output, Map<String, ?> value) throws IOException {
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            new ReservedOutput(channel).write(reportBytes(value));
        }
    }

    private static byte[] reportBytes(Map<String, ?> value) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        if (bytes.length <= MAX_REPORT_BYTES) return bytes;
        if (!value.containsKey("structuredOutput")) {
            throw new IllegalStateException("POC report exceeds the output limit");
        }
        Map<String, Object> bounded = new LinkedHashMap<>(value);
        bounded.put("structuredOutput", null);
        bounded.put("structuredOutputStatus", "OMITTED_REPORT_SIZE_LIMIT");
        bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(bounded);
        if (bytes.length > MAX_REPORT_BYTES) {
            throw new IllegalStateException("POC report exceeds the output limit");
        }
        return bytes;
    }

    private static String safeMessage(Exception failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        if (current instanceof IllegalArgumentException && SAFE_MESSAGES.contains(current.getMessage())) {
            return current.getMessage();
        }
        return "execution failed";
    }

    private record Preflight(Path input, Path output, long inputBytes, List<TranscriptSegment> transcript,
                             ObjectNode payload, String priceVersion, OpenAiPocLimits limits) {}

    private record Completed(JsonNode structuredOutput, BigDecimal estimatedCost) {}

    private record ReservedOutput(FileChannel channel) implements AutoCloseable {
        private ReservedOutput {
            Objects.requireNonNull(channel);
        }

        void write(byte[] bytes) throws IOException {
            channel.truncate(0);
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }

        @Override public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The report write result remains authoritative.
            }
        }
    }

    private record Request(boolean execute, boolean syntheticApproved, Path input, Path output,
                           Duration timeout, int outputTokenCap, BigDecimal maxUsd, BigDecimal inputRate,
                           BigDecimal outputRate, String priceVersion) {
        static Request parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean execute = false;
            boolean syntheticApproved = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--execute".equals(argument)) { execute = uniqueFlag(values, argument); continue; }
                if ("--synthetic-approved".equals(argument)) {
                    syntheticApproved = uniqueFlag(values, argument);
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
            Duration timeout = parseTimeout(required(values, "--timeout-seconds"));
            int outputTokenCap = positiveInteger(required(values, "--output-token-cap"), 4_096);
            BigDecimal maxUsd = positiveDecimal(required(values, "--max-usd"));
            BigDecimal inputRate = positiveDecimal(required(values, "--input-usd-per-million"));
            BigDecimal outputRate = positiveDecimal(required(values, "--output-usd-per-million"));
            String priceVersion = required(values, "--price-version");
            if (!PRICE_VERSION.matcher(priceVersion).matches()) {
                throw new IllegalArgumentException("Invalid price version");
            }
            values.keySet().removeAll(List.of("--execute", "--synthetic-approved", "--input", "--output",
                    "--timeout-seconds", "--output-token-cap", "--max-usd", "--input-usd-per-million",
                    "--output-usd-per-million", "--price-version"));
            if (!values.isEmpty()) throw new IllegalArgumentException("Unknown command option");
            if (execute && !syntheticApproved) {
                throw new IllegalArgumentException("Synthetic transcript approval is required");
            }
            return new Request(execute, syntheticApproved, input, output, timeout, outputTokenCap, maxUsd,
                    inputRate, outputRate, priceVersion);
        }

        private static boolean uniqueFlag(Map<String, String> values, String name) {
            if (values.putIfAbsent(name, "true") != null) {
                throw new IllegalArgumentException("Duplicate command option");
            }
            return true;
        }

        private static String required(Map<String, String> values, String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required command option");
            return value;
        }

        private static Duration parseTimeout(String value) {
            try {
                BigDecimal seconds = new BigDecimal(value);
                if (seconds.signum() <= 0 || seconds.compareTo(BigDecimal.valueOf(120)) > 0) {
                    throw new NumberFormatException();
                }
                return Duration.ofNanos(seconds.movePointRight(9).longValueExact());
            } catch (ArithmeticException | NumberFormatException failure) {
                throw new IllegalArgumentException("Timeout must be positive and at most 120 seconds");
            }
        }

        private static int positiveInteger(String value, int maximum) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1 || parsed > maximum) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Output token cap must be positive and at most 4096");
            }
        }

        private static BigDecimal positiveDecimal(String value) {
            try {
                if (!DECIMAL.matcher(value).matches()) throw new NumberFormatException();
                BigDecimal parsed = new BigDecimal(value);
                if (parsed.signum() <= 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Cost values must be positive decimals");
            }
        }
    }
}
