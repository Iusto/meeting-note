package dev.aimeeting.api.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String ownerToken,
        List<String> allowedOrigins,
        Path recordingRoot,
        boolean paidApiTestsEnabled,
        long fakeEventDelayMs) {

    public AppProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        recordingRoot = recordingRoot == null ? Path.of("./data/recordings") : recordingRoot;
        fakeEventDelayMs = fakeEventDelayMs <= 0 ? 100 : Math.min(fakeEventDelayMs, 5_000);
    }
}
