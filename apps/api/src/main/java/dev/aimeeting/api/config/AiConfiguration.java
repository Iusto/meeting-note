package dev.aimeeting.api.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class AiConfiguration {
    private static final int DEFAULT_MAXIMUM_AUDIO_SECONDS = 14_400;
    private static final BigDecimal DEFAULT_MAXIMUM_MEETING_USD = new BigDecimal("3.00");
    private static final BigDecimal DEFAULT_DEEPGRAM_RATE = new BigDecimal("0.011");
    private static final BigDecimal DEFAULT_OPENAI_INPUT_RATE = new BigDecimal("2.00");
    private static final BigDecimal DEFAULT_OPENAI_OUTPUT_RATE = new BigDecimal("10.00");
    private static final int DEFAULT_OUTPUT_TOKEN_CAP = 2_000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 45;

    private dev.aimeeting.api.settings.SettingsRepository settings;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSettings(dev.aimeeting.api.settings.SettingsRepository settings) { this.settings = settings; }

    private final AppProperties properties;
    private final Environment environment;
    private final List<String> validationErrors = new ArrayList<>();
    private final int maximumAudioSeconds;
    private final BigDecimal maximumMeetingUsd;
    private final BigDecimal deepgramRatePerMinute;
    private final String priceVersion;
    private final BigDecimal openAiInputRate;
    private final BigDecimal openAiOutputRate;
    private final int outputTokenCap;
    private final int timeoutSeconds;

    public AiConfiguration(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
        maximumAudioSeconds = integer("ai.maximum-audio-seconds", DEFAULT_MAXIMUM_AUDIO_SECONDS, 1, 14_400);
        maximumMeetingUsd = decimal("ai.maximum-meeting-usd", DEFAULT_MAXIMUM_MEETING_USD,
                new BigDecimal("0.01"), new BigDecimal("100.00"));
        deepgramRatePerMinute = decimal("ai.deepgram-rate-per-minute", DEFAULT_DEEPGRAM_RATE,
                new BigDecimal("0.000001"), BigDecimal.ONE);
        openAiInputRate = decimal("ai.openai-input-rate-per-million", DEFAULT_OPENAI_INPUT_RATE,
                new BigDecimal("0.000001"), new BigDecimal("1000"));
        openAiOutputRate = decimal("ai.openai-output-rate-per-million", DEFAULT_OPENAI_OUTPUT_RATE,
                new BigDecimal("0.000001"), new BigDecimal("1000"));
        outputTokenCap = integer("ai.output-token-cap", DEFAULT_OUTPUT_TOKEN_CAP, 1, 2_000);
        timeoutSeconds = integer("ai.timeout-seconds", DEFAULT_TIMEOUT_SECONDS, 1, 120);
        String configuredVersion = environment.getProperty("ai.price-version", "config-record").trim();
        if (!configuredVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            validationErrors.add("ai.price-version must use 1-64 letters, digits, dots, underscores, or hyphens");
            configuredVersion = "config-record";
        }
        priceVersion = configuredVersion;
    }

    public boolean enabled() { return properties.paidApiTestsEnabled(); }

    public boolean speechAvailable() { return enabled() && !deepgramApiKey().isBlank(); }

    public boolean llmAvailable() { return enabled() && !openAiApiKey().isBlank(); }

    public int maximumAudioSeconds() { return maximumAudioSeconds; }

    public BigDecimal maximumMeetingUsd() { return settings == null ? maximumMeetingUsd : settings.get().perMeetingBudgetUsd(); }

    public BigDecimal deepgramRatePerMinute() { return deepgramRatePerMinute; }

    public String priceVersion() { return priceVersion; }

    public String deepgramApiKey() { return secret("DEEPGRAM_API_KEY"); }

    public String openAiApiKey() { return secret("OPENAI_API_KEY"); }

    public BigDecimal openAiInputRate() { return openAiInputRate; }

    public BigDecimal openAiOutputRate() { return openAiOutputRate; }

    public int outputTokenCap() { return outputTokenCap; }

    public int timeoutSeconds() { return timeoutSeconds; }

    public void requireSpeech() {
        if (!speechAvailable()) throw new IllegalStateException("Paid speech AI is not configured");
    }

    public void requireLlm() {
        if (!llmAvailable()) throw new IllegalStateException("Paid language-model AI is not configured");
    }

    void validatePaidMode() {
        if (!validationErrors.isEmpty()) {
            throw new IllegalStateException("Invalid paid AI configuration: " + String.join("; ", validationErrors));
        }
        if (deepgramApiKey().isBlank() || openAiApiKey().isBlank()) {
            throw new IllegalStateException("Paid AI requires both DEEPGRAM_API_KEY and OPENAI_API_KEY");
        }
    }

    private String secret(String name) {
        String value = environment.getProperty(name);
        return value == null ? "" : value.trim();
    }

    private int integer(String name, int fallback, int minimum, int maximum) {
        String raw = environment.getProperty(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException ignored) {
            // The safe validation error below deliberately excludes the supplied value.
        }
        validationErrors.add(name + " must be between " + minimum + " and " + maximum);
        return fallback;
    }

    private BigDecimal decimal(String name, BigDecimal fallback, BigDecimal minimum, BigDecimal maximum) {
        String raw = environment.getProperty(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0) return value;
        } catch (NumberFormatException ignored) {
            // The safe validation error below deliberately excludes the supplied value.
        }
        validationErrors.add(name + " must be between " + minimum + " and " + maximum);
        return fallback;
    }
}
