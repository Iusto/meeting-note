package dev.aimeeting.api.provider.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Stateless opt-in Responses API transport. The application service owns production activation. */
public final class OpenAiPocClient {
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    // Conservative byte-based allowance for the bounded JSON request and API framing, not exact tokenization.
    private static final int INPUT_FRAMING_RESERVE = 4096;

    public enum Status { COMPLETED, REFUSED, INCOMPLETE, FAILED, HTTP_ERROR }
    public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {}
    public record Response(Status status, String responseId, String outputJson, TokenUsage usage) {}

    private OpenAiPocClient() {}

    public static CompletableFuture<Response> request(boolean paidOptIn, String apiKey,
                                                       ObjectNode payload, OpenAiPocLimits limits) {
        if (!paidOptIn) throw new IllegalStateException("Paid OpenAI POC access is disabled");
        if (apiKey == null || apiKey.isBlank() || apiKey.codePoints().anyMatch(c ->
                Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Invalid OpenAI API key");
        }
        return open(ENDPOINT, "Bearer " + apiKey, payload, limits);
    }

    static CompletableFuture<Response> requestLoopback(URI endpoint, ObjectNode payload, OpenAiPocLimits limits) {
        Objects.requireNonNull(endpoint);
        if (!"http".equals(endpoint.getScheme())
                || !List.of("127.0.0.1", "::1", "[::1]").contains(endpoint.getHost())
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                || !"/v1/responses".equals(endpoint.getRawPath()) || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid offline OpenAI endpoint");
        }
        return open(endpoint, "Bearer offline-test-token", payload, limits);
    }

    private static CompletableFuture<Response> open(URI endpoint, String authorization,
                                                      ObjectNode payload, OpenAiPocLimits limits) {
        Objects.requireNonNull(limits);
        byte[] body = requestBytes(payload, limits);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "openai-poc-timeout");
            thread.setDaemon(true);
            return thread;
        });
        HttpClient client;
        CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            client = HttpClient.newBuilder().executor(executor).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(limits.timeout()).build();
        } catch (RuntimeException failure) {
            scheduler.shutdownNow();
            executor.shutdownNow();
            return CompletableFuture.failedFuture(failed("OpenAI connection failed"));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(limits.timeout())
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            exchange = client.sendAsync(request, ignored -> new BoundedBodySubscriber());
        } catch (RuntimeException failure) {
            client.shutdownNow();
            scheduler.shutdownNow();
            executor.shutdownNow();
            return CompletableFuture.failedFuture(failed("OpenAI connection failed"));
        }
        CompletableFuture<Response> result = new CompletableFuture<>();
        var deadline = scheduler.schedule(() -> result.completeExceptionally(failed("OpenAI request timed out")),
                limits.timeout().toNanos(), TimeUnit.NANOSECONDS);
        result.whenComplete((ignored, failure) -> {
            deadline.cancel(false);
            exchange.cancel(true);
            client.shutdownNow();
            executor.shutdownNow();
            scheduler.shutdownNow();
        });
        exchange.whenComplete((response, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failed("OpenAI request failed"));
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.complete(new Response(Status.HTTP_ERROR, null, null, null));
                return;
            }
            try {
                result.complete(parse(response.body()));
            } catch (RuntimeException invalid) {
                result.completeExceptionally(failed("Invalid OpenAI response"));
            }
        });
        return result;
    }

    static void validateRequest(ObjectNode payload, OpenAiPocLimits limits) {
        Objects.requireNonNull(limits);
        requestBytes(payload, limits);
    }

    private static byte[] requestBytes(ObjectNode supplied, OpenAiPocLimits limits) {
        Objects.requireNonNull(supplied);
        ObjectNode payload = supplied.deepCopy();
        if (!"gpt-6.1-sol".equals(payload.path("model").asText())
                || !payload.has("store") || !payload.get("store").isBoolean() || payload.get("store").booleanValue()
                || !"default".equals(payload.path("service_tier").asText())
                || !"low".equals(payload.path("reasoning").path("effort").asText())
                || !payload.path("max_output_tokens").isIntegralNumber()
                || !payload.path("max_output_tokens").canConvertToInt()
                || payload.path("max_output_tokens").intValue() != limits.maxOutputTokens()
                || !payload.path("instructions").isTextual() || !payload.path("input").isArray()
                || !"json_schema".equals(payload.path("text").path("format").path("type").asText())
                || !payload.path("text").path("format").path("strict").isBoolean()
                || !payload.path("text").path("format").path("strict").booleanValue()) {
            throw new IllegalArgumentException("Invalid OpenAI POC request");
        }
        var names = payload.fieldNames();
        var allowed = List.of("model", "store", "service_tier", "reasoning", "max_output_tokens", "instructions", "input", "text");
        while (names.hasNext()) if (!allowed.contains(names.next())) {
            throw new IllegalArgumentException("Invalid OpenAI POC request");
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(payload);
            if (bytes.length > MAX_REQUEST_BYTES || bytes.length + INPUT_FRAMING_RESERVE > limits.maxInputTokens()) {
                throw new IllegalArgumentException("OpenAI request exceeds the input reservation");
            }
            return bytes;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid OpenAI POC request");
        }
    }

    private static Response parse(byte[] bytes) {
        try {
            JsonNode root = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
            if (root == null || !root.isObject()) throw failed("Invalid OpenAI response");
            String id = text(root, "id", 256);
            TokenUsage usage = usage(root.get("usage"));
            if (root.hasNonNull("error")) return new Response(Status.FAILED, id, null, usage);
            String status = text(root, "status", 32);
            if ("incomplete".equals(status)) return new Response(Status.INCOMPLETE, id, null, usage);
            if ("failed".equals(status)) return new Response(Status.FAILED, id, null, usage);
            if (!"completed".equals(status) || usage == null || !root.path("output").isArray()
                    || root.path("output").size() > 100) throw failed("Invalid OpenAI response");
            String output = null;
            boolean refusal = false;
            for (JsonNode item : root.path("output")) {
                if ("reasoning".equals(item.path("type").asText())) continue;
                if (!"message".equals(item.path("type").asText()) || !"assistant".equals(item.path("role").asText())
                        || !"completed".equals(item.path("status").asText()) || !item.path("content").isArray()
                        || item.path("content").size() > 100) throw failed("Invalid OpenAI response");
                for (JsonNode content : item.path("content")) {
                    if ("refusal".equals(content.path("type").asText())) refusal = true;
                    else if ("output_text".equals(content.path("type").asText()) && output == null) {
                        output = text(content, "text", MAX_OUTPUT_CHARS);
                    } else throw failed("Invalid OpenAI response");
                }
            }
            if (refusal) return new Response(Status.REFUSED, id, null, usage);
            if (output == null || output.isBlank()) throw failed("Invalid OpenAI response");
            return new Response(Status.COMPLETED, id, output, usage);
        } catch (Exception invalid) {
            throw failed("Invalid OpenAI response");
        }
    }

    private static TokenUsage usage(JsonNode node) {
        if (node == null || node.isNull()) return null;
        long input = tokens(node, "input_tokens");
        long output = tokens(node, "output_tokens");
        long total = tokens(node, "total_tokens");
        if (Math.addExact(input, output) != total) throw failed("Invalid OpenAI response");
        return new TokenUsage(input, output, total);
    }

    private static long tokens(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw failed("Invalid OpenAI response");
        return value.longValue();
    }

    private static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > maximum)
            throw failed("Invalid OpenAI response");
        return value.textValue();
    }

    private static IllegalStateException failed(String message) { return new IllegalStateException(message); }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    bytes.reset();
                    body.completeExceptionally(failed("OpenAI response exceeds the POC limit"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) {
            bytes.reset();
            body.completeExceptionally(failed("OpenAI response failed"));
        }
        @Override public void onComplete() {
            body.complete(bytes.toByteArray());
            bytes.reset();
        }
    }
}
