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
                configuration.maximumAudioSeconds(), configuration.maximumMeetingUsd());
    }

    public record Response(boolean enabled, boolean speechAvailable, boolean llmAvailable,
                           int maximumAudioSeconds, BigDecimal maximumMeetingUsd) {}
}
