package dev.aimeeting.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PaidApiGuardTest {
    @Test
    void disabledModeRequiresNoProviderSecrets() {
        AiConfiguration configuration = configuration(false, new MockEnvironment());

        assertThatCode(() -> new PaidApiGuard(configuration).validateOptIn()).doesNotThrowAnyException();
        assertThat(configuration.enabled()).isFalse();
        assertThat(configuration.speechAvailable()).isFalse();
        assertThat(configuration.llmAvailable()).isFalse();
        assertThat(configuration.maximumAudioSeconds()).isEqualTo(14_400);
        assertThat(configuration.maximumMeetingUsd()).isEqualByComparingTo("3.00");
        assertThat(configuration.deepgramRatePerMinute()).isEqualByComparingTo("0.011");
        assertThat(configuration.openAiInputRate()).isEqualByComparingTo("2.00");
        assertThat(configuration.openAiOutputRate()).isEqualByComparingTo("10.00");
        assertThat(configuration.outputTokenCap()).isEqualTo(2_000);
        assertThat(configuration.timeoutSeconds()).isEqualTo(45);
        assertThat(configuration.priceVersion()).isEqualTo("config-record");
    }

    @Test
    void paidModeRequiresBothKeysWithoutDisclosingTheirValues() {
        MockEnvironment environment = new MockEnvironment().withProperty("DEEPGRAM_API_KEY", "deepgram-secret");
        PaidApiGuard guard = new PaidApiGuard(configuration(true, environment));

        assertThatThrownBy(guard::validateOptIn)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPGRAM_API_KEY")
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageNotContaining("deepgram-secret");
    }

    @Test
    void explicitOptInWithValidKeysEnablesBothCapabilities() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DEEPGRAM_API_KEY", "deepgram-secret")
                .withProperty("OPENAI_API_KEY", "openai-secret")
                .withProperty("ai.maximum-audio-seconds", "60")
                .withProperty("ai.maximum-meeting-usd", "0.50");
        AiConfiguration configuration = configuration(true, environment);

        assertThatCode(() -> new PaidApiGuard(configuration).validateOptIn()).doesNotThrowAnyException();
        assertThat(configuration.speechAvailable()).isTrue();
        assertThat(configuration.llmAvailable()).isTrue();
        assertThat(configuration.maximumAudioSeconds()).isEqualTo(60);
        assertThat(configuration.maximumMeetingUsd()).isEqualByComparingTo("0.50");
    }

    @Test
    void invalidPaidLimitsFailClosedButRemainSafeWhenMocksAreSelected() {
        MockEnvironment invalid = new MockEnvironment()
                .withProperty("DEEPGRAM_API_KEY", "deepgram-secret")
                .withProperty("OPENAI_API_KEY", "openai-secret")
                .withProperty("ai.output-token-cap", "2001");

        assertThatCode(() -> new PaidApiGuard(configuration(false, invalid)).validateOptIn())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new PaidApiGuard(configuration(true, invalid)).validateOptIn())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ai.output-token-cap")
                .hasMessageNotContaining("openai-secret");
    }

    private AiConfiguration configuration(boolean enabled, MockEnvironment environment) {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of(),
                Path.of("target"), enabled, 100);
        return new AiConfiguration(properties, environment);
    }
}
