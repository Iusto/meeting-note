package dev.aimeeting.api.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAiPocRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SEGMENT_ID = "00000000-0000-0000-0000-000000000001";

    @TempDir Path temporary;

    @Test
    void dryRunDoesNotReadKeysDispatchOrCopyTranscriptIntoReport() throws Exception {
        Path input = snapshot("snapshot.json");
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        OpenAiPocRunner runner = new OpenAiPocRunner(name -> {
            environmentReads.incrementAndGet();
            return null;
        }, (key, payload, limits) -> {
            dispatches.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("must not dispatch"));
        });

        int exit = runner.run(arguments(input, output));

        assertThat(exit).isZero();
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
        JsonNode report = JSON.readTree(output.toFile());
        assertThat(report.path("status").asText()).isEqualTo("dry-run");
        assertThat(report.path("measurementClassification").asText()).isEqualTo("PREFLIGHT_ONLY");
        assertThat(report.path("actualCostUsd").asText()).isEqualTo("UNKNOWN");
        assertThat(report.path("structuredOutput").isNull()).isTrue();
        assertThat(report.toString()).doesNotContain("SECRET_TRANSCRIPT_TEXT");
    }

    @Test
    void executeRequiresExactPaidOptInBeforeReadingOpenAiKeyOrDispatching() throws Exception {
        Path input = snapshot("snapshot.json");
        Path output = temporary.resolve("report.json");
        List<String> environmentReads = new ArrayList<>();
        AtomicInteger dispatches = new AtomicInteger();
        OpenAiPocRunner runner = new OpenAiPocRunner(name -> {
            environmentReads.add(name);
            return "false";
        }, (key, payload, limits) -> {
            dispatches.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("must not dispatch"));
        });

        int exit = runner.run(executeArguments(input, output));

        assertThat(exit).isEqualTo(2);
        assertThat(environmentReads).containsExactly("PAID_API_TESTS_ENABLED");
        assertThat(dispatches).hasValue(0);
        assertThat(JSON.readTree(output.toFile()).path("error").asText()).isEqualTo("POC_EXECUTION_FAILED");
    }

    @Test
    void rejectsOverBudgetReservationBeforeEnvironmentReadOrDispatch() throws Exception {
        Path input = snapshot("snapshot.json");
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        OpenAiPocRunner runner = countingRunner(environmentReads, dispatches, completed());
        List<String> args = new ArrayList<>(List.of(arguments(input, output)));
        args.set(args.indexOf("--max-usd") + 1, "0.000001");

        int exit = runner.run(args.toArray(String[]::new));

        assertThat(exit).isEqualTo(2);
        assertThat(output).doesNotExist();
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
    }

    @Test
    void rejectsNonStrictOrNonSyntheticInputBeforeEnvironmentReadOrDispatch() throws Exception {
        Path input = temporary.resolve("invalid.json");
        Files.writeString(input, """
                {"schemaVersion":1,"measurementOrigin":"recorded/real","segments":[],"raw":"private"}
                """);
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        OpenAiPocRunner runner = countingRunner(environmentReads, dispatches, completed());

        int exit = runner.run(executeArguments(input, output));

        assertThat(exit).isEqualTo(2);
        assertThat(output).doesNotExist();
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
    }

    @Test
    void refusesExistingOutputBeforeEnvironmentReadOrDispatch() throws Exception {
        Path input = snapshot("snapshot.json");
        Path output = temporary.resolve("existing.json");
        Files.writeString(output, "keep");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        OpenAiPocRunner runner = countingRunner(environmentReads, dispatches, completed());

        int exit = runner.run(executeArguments(input, output));

        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(output)).isEqualTo("keep");
        assertThat(environmentReads).hasValue(0);
        assertThat(dispatches).hasValue(0);
    }

    @Test
    void failsClosedOnInvalidUsageAndDoesNotExposeProviderValues() throws Exception {
        Path input = snapshot("snapshot.json");
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        OpenAiPocClient.Response invalid = new OpenAiPocClient.Response(OpenAiPocClient.Status.COMPLETED,
                "secret-request-id", summaryJson(), new OpenAiPocClient.TokenUsage(10, -1, 9));
        OpenAiPocRunner runner = countingRunner(environmentReads, dispatches, invalid);

        int exit = runner.run(executeArguments(input, output));

        assertThat(exit).isEqualTo(2);
        assertThat(environmentReads).hasValue(2);
        assertThat(dispatches).hasValue(1);
        assertThat(Files.readString(output)).doesNotContain("secret-request-id", "test-openai-key")
                .contains("POC_EXECUTION_FAILED");
    }

    @Test
    void writesValidatedStructuredOutputRequestIdUsageAndEstimate() throws Exception {
        Path input = snapshot("snapshot.json");
        Path output = temporary.resolve("report.json");
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        OpenAiPocRunner runner = countingRunner(environmentReads, dispatches, completed());

        int exit = runner.run(executeArguments(input, output));

        assertThat(exit).isZero();
        assertThat(environmentReads).hasValue(2);
        assertThat(dispatches).hasValue(1);
        JsonNode report = JSON.readTree(output.toFile());
        assertThat(report.path("requestId").asText()).isEqualTo("response-1");
        assertThat(report.at("/usage/inputTokens").asLong()).isEqualTo(100);
        assertThat(report.at("/usage/outputTokens").asLong()).isEqualTo(20);
        assertThat(report.at("/structuredOutput/summary").asText()).isEqualTo("Synthetic summary");
        assertThat(report.path("estimatedCostUsd").asText()).isEqualTo("0.000400");
        assertThat(report.path("actualCostUsd").asText()).isEqualTo("UNKNOWN");
        assertThat(report.toString()).doesNotContain("test-openai-key");
    }

    @Test
    void oversizedStructuredOutputIsOmittedWithoutLosingPaidEvidence() throws Exception {
        Path output = temporary.resolve("oversized-report.json");
        Files.write(output, new byte[0]);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "completed");
        report.put("requestId", "response-oversized");
        report.put("usage", new OpenAiPocClient.TokenUsage(120, 30, 150));
        report.put("estimatedCostUsd", "0.000540");
        report.put("actualCostUsd", "UNKNOWN");
        report.put("structuredOutput", Map.of("summary", "x".repeat(600 * 1024)));
        Method write = OpenAiPocRunner.class.getDeclaredMethod("write", Path.class, Map.class);
        write.setAccessible(true);

        write.invoke(null, output, report);

        JsonNode saved = JSON.readTree(output.toFile());
        assertThat(saved.path("requestId").asText()).isEqualTo("response-oversized");
        assertThat(saved.at("/usage/totalTokens").asLong()).isEqualTo(150);
        assertThat(saved.path("estimatedCostUsd").asText()).isEqualTo("0.000540");
        assertThat(saved.path("actualCostUsd").asText()).isEqualTo("UNKNOWN");
        assertThat(saved.path("structuredOutput").isNull()).isTrue();
        assertThat(saved.path("structuredOutputStatus").asText()).isEqualTo("OMITTED_REPORT_SIZE_LIMIT");
        assertThat(Files.size(output)).isLessThanOrEqualTo(512L * 1024);
    }

    @Test
    void reportWritesStayOnReservedHandleWhenOutputPathIsReplaced() throws Exception {
        Path input = snapshot("snapshot.json");
        Path output = temporary.resolve("report.json");
        Path reservedWitness = temporary.resolve("reserved-witness.json");
        AtomicBoolean replacementSupported = new AtomicBoolean();
        OpenAiPocRunner runner = new OpenAiPocRunner(name ->
                "PAID_API_TESTS_ENABLED".equals(name) ? "true" : "test-openai-key",
                (key, payload, limits) -> {
                    try {
                        Files.createLink(reservedWitness, output);
                        Files.delete(output);
                        Files.writeString(output, "replacement");
                        replacementSupported.set(true);
                    } catch (Exception unsupported) {
                        return CompletableFuture.failedFuture(unsupported);
                    }
                    return CompletableFuture.completedFuture(completed());
                });

        int exit = runner.run(executeArguments(input, output));
        Assumptions.assumeTrue(replacementSupported.get(),
                "Platform does not allow replacing an open reserved output path");

        assertThat(exit).isZero();
        assertThat(Files.readString(output)).isEqualTo("replacement");
        JsonNode saved = JSON.readTree(reservedWitness.toFile());
        assertThat(saved.path("status").asText()).isEqualTo("completed");
        assertThat(saved.path("requestId").asText()).isEqualTo("response-1");
    }

    private OpenAiPocRunner countingRunner(AtomicInteger environmentReads, AtomicInteger dispatches,
                                           OpenAiPocClient.Response response) {
        return new OpenAiPocRunner(name -> {
            environmentReads.incrementAndGet();
            return "PAID_API_TESTS_ENABLED".equals(name) ? "true" : "test-openai-key";
        }, (key, payload, limits) -> {
            dispatches.incrementAndGet();
            assertThat(key).isEqualTo("test-openai-key");
            assertThat(payload.path("model").asText()).isEqualTo("gpt-6.1-sol");
            assertThat(payload.path("store").asBoolean()).isFalse();
            assertThat(payload.path("service_tier").asText()).isEqualTo("default");
            assertThat(payload.at("/reasoning/effort").asText()).isEqualTo("low");
            return CompletableFuture.completedFuture(response);
        });
    }

    private Path snapshot(String name) throws Exception {
        Path path = temporary.resolve(name);
        Files.writeString(path, """
                {"schemaVersion":1,"measurementOrigin":"synthetic/offline","segments":[
                  {"id":"%s","revision":0,"userLocked":false,
                   "displayText":"SECRET_TRANSCRIPT_TEXT"}]}
                """.formatted(SEGMENT_ID));
        return path;
    }

    private String[] arguments(Path input, Path output) {
        return new String[] {"--input", input.toString(), "--output", output.toString(),
                "--timeout-seconds", "2", "--output-token-cap", "100",
                "--max-usd", "1.00", "--input-usd-per-million", "2.00",
                "--output-usd-per-million", "10.00", "--price-version", "test-2026-10-21"};
    }

    private String[] executeArguments(Path input, Path output) {
        List<String> values = new ArrayList<>(List.of(arguments(input, output)));
        values.add("--execute");
        values.add("--synthetic-approved");
        return values.toArray(String[]::new);
    }

    private OpenAiPocClient.Response completed() {
        return new OpenAiPocClient.Response(OpenAiPocClient.Status.COMPLETED, "response-1", summaryJson(),
                new OpenAiPocClient.TokenUsage(100, 20, 120));
    }

    private String summaryJson() {
        return """
                {"summary":"Synthetic summary","sourceSegmentIds":["%s"],
                 "decisions":[],"actionItems":[],"unresolved":[]}
                """.formatted(SEGMENT_ID);
    }
}
