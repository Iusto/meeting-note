package dev.aimeeting.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class PaidApiGuard {
    private final AppProperties properties;

    public PaidApiGuard(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void rejectPaidModeInPhaseZero() {
        if (properties.paidApiTestsEnabled()) {
            throw new IllegalStateException("Paid API tests are unavailable in Phase 0");
        }
    }
}
