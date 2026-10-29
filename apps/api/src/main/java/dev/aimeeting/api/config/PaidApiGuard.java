package dev.aimeeting.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class PaidApiGuard {
    private final AiConfiguration configuration;

    public PaidApiGuard(AiConfiguration configuration) {
        this.configuration = configuration;
    }

    @PostConstruct
    void validateOptIn() {
        if (configuration.enabled()) configuration.validatePaidMode();
    }
}
