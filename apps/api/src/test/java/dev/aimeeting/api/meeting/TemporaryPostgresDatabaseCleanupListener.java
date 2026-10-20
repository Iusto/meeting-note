package dev.aimeeting.api.meeting;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

final class TemporaryPostgresDatabaseCleanupListener implements TestExecutionListener, Ordered {
    @Override
    public int getOrder() {
        // afterTestClass listeners run in reverse order. DirtiesContext uses 3000,
        // so the application context and its connection pool close before this cleanup.
        return 2500;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        PostgresMigrationIntegrationTest.dropIsolatedDatabase();
    }
}
