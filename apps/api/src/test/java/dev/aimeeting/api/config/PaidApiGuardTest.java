package dev.aimeeting.api.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaidApiGuardTest {
    @Test
    void paidModeFailsClosedDuringPhaseZero() {
        PaidApiGuard disabled = new PaidApiGuard(properties(false));
        assertThatCode(disabled::rejectPaidModeInPhaseZero).doesNotThrowAnyException();

        PaidApiGuard enabled = new PaidApiGuard(properties(true));
        assertThatThrownBy(enabled::rejectPaidModeInPhaseZero)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable in Phase 0");
    }

    private AppProperties properties(boolean enabled) {
        return new AppProperties("a-valid-owner-token-000000000000", List.of(), Path.of("target"), enabled, 100);
    }
}
