package dev.aimeeting.api.config;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/configuration")
public class AiConfigurationController {
    private final AiConfiguration configuration;

    public AiConfigurationController(AiConfiguration configuration) {
        this.configuration = configuration;
    }

    @GetMapping
    public Response get() {
        return new Response(configuration.enabled(), configuration.speechAvailable(), configuration.llmAvailable(),
                configuration.maximumAudioSeconds(), configuration.maximumMeetingUsd(), configuration.codexEnabled() ? "CODEX" : "OPENAI");
    }

    @GetMapping("/usage")
    public com.fasterxml.jackson.databind.JsonNode usage() {
        if (!configuration.codexEnabled()) throw new dev.aimeeting.api.web.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "CODEX_NOT_CONFIGURED", "Codex 모드에서만 구독 사용량을 조회할 수 있습니다.");
        return dev.aimeeting.api.provider.openai.CodexBridgeClient.usage(configuration.codexBridgeUrl(), configuration.codexBridgeToken());
    }

    public record Response(boolean enabled, boolean speechAvailable, boolean llmAvailable,
                           int maximumAudioSeconds, BigDecimal maximumMeetingUsd, String llmProvider) {}
}
