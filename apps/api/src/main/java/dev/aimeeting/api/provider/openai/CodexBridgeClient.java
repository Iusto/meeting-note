package dev.aimeeting.api.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aimeeting.api.web.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;

public final class CodexBridgeClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private CodexBridgeClient() {}

    public static com.fasterxml.jackson.databind.JsonNode usage(String endpoint, String token) {
        if (!token.matches("[a-f0-9]{64}")) throw error("CODEX_NOT_CONFIGURED");
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !java.util.List.of("host.docker.internal", "127.0.0.1").contains(uri.getHost())
                || uri.getPort() != 8767 || !"/generate".equals(uri.getPath()) || uri.getRawQuery() != null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw error("CODEX_NOT_CONFIGURED");
        try {
            var request = HttpRequest.newBuilder(uri.resolve("/usage")).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).GET().build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(1024 * 1024 + 1);
                if (response.statusCode() != 200 || bytes.length > 1024 * 1024) throw error("CODEX_USAGE_UNAVAILABLE");
                return JSON.readTree(bytes);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw error("CODEX_USAGE_UNAVAILABLE");
        } catch (Exception unavailable) { throw error("CODEX_USAGE_UNAVAILABLE"); }
    }

    public static CompletableFuture<OpenAiPocClient.Response> request(String endpoint, String token, ObjectNode payload) {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !java.util.List.of("host.docker.internal", "127.0.0.1").contains(uri.getHost())
                || uri.getPort() != 8767 || !"/generate".equals(uri.getPath()) || uri.getRawQuery() != null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || !token.matches("[a-f0-9]{64}")) {
            throw error("CODEX_NOT_CONFIGURED");
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(610))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).handle((response, failure) -> {
            if (failure != null) throw error("CODEX_UNAVAILABLE");
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw error("CODEX_INVALID_RESULT");
                var root = JSON.readTree(bytes);
                if (response.statusCode() != 200) throw error(root.path("code").asText());
                String output = root.path("outputJson").asText();
                if (output.isBlank()) throw error("CODEX_INVALID_RESULT");
                return new OpenAiPocClient.Response(OpenAiPocClient.Status.COMPLETED, null, output, null);
            } catch (ApiException known) { throw known; }
            catch (Exception invalid) { throw error("CODEX_INVALID_RESULT"); }
        });
    }

    static ApiException error(String code) {
        String message = switch (code) {
            case "CODEX_USAGE_UNAVAILABLE" -> "Codex 사용량을 조회하지 못했습니다. PC의 Codex 로그인과 연결을 확인하고 다시 시도하세요.";
            case "CODEX_LOGIN_REQUIRED" -> "서버 PC의 Codex 로그인이 필요합니다. ChatGPT 계정으로 다시 로그인하세요.";
            case "CODEX_LIMIT_REACHED" -> "Codex 요금제의 사용 한도에 도달했습니다. 한도가 회복된 뒤 다시 시도하세요.";
            case "CODEX_MODEL_UNAVAILABLE" -> "Codex에서 GPT-6.1 Sol을 사용할 수 없습니다. 계정의 모델 접근 권한을 확인하세요.";
            case "CODEX_TIMEOUT" -> "Codex 요약 시간이 초과되었습니다. 잠시 후 다시 확인하세요.";
            case "AI_BUSY" -> "다른 AI 작업이 진행 중입니다. 완료된 뒤 다시 시도하세요.";
            default -> "서버 PC의 Codex 작업을 완료하지 못했습니다. Codex 연결과 로그인 상태를 확인하세요.";
        };
        String safeCode = java.util.Set.of("CODEX_LOGIN_REQUIRED", "CODEX_LIMIT_REACHED", "CODEX_MODEL_UNAVAILABLE",
                "CODEX_TIMEOUT", "AI_BUSY", "CODEX_UNAVAILABLE", "CODEX_INVALID_RESULT", "CODEX_NOT_CONFIGURED", "CODEX_USAGE_UNAVAILABLE").contains(code)
                ? code : "CODEX_GENERATION_FAILED";
        return new ApiException(HttpStatus.BAD_GATEWAY, safeCode, message);
    }
}
