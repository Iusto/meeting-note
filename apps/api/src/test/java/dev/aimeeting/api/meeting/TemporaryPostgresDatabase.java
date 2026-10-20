package dev.aimeeting.api.meeting;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

final class TemporaryPostgresDatabase implements AutoCloseable {
    static final String DATABASE_PREFIX = "ai_meeting_test_";

    private final String maintenanceUrl;
    private final String username;
    private final String password;
    private final String sourceDatabaseName;
    private final String databaseName;
    private final String databaseUrl;
    private boolean closed;
    private final Thread shutdownHook;

    static TemporaryPostgresDatabase create(String maintenanceUrl, String username, String password) {
        JdbcUrl parsed = JdbcUrl.parse(maintenanceUrl);
        String databaseName = DATABASE_PREFIX + UUID.randomUUID().toString().replace("-", "");
        requireOwnedName(databaseName);

        boolean created = false;
        try (Connection connection = DriverManager.getConnection(maintenanceUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quotedIdentifier(databaseName));
            created = true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create an isolated PostgreSQL test database", exception);
        }

        String databaseUrl = parsed.withDatabase(databaseName);
        try {
            return new TemporaryPostgresDatabase(
                    maintenanceUrl, username, password, parsed.databaseName(), databaseName, databaseUrl);
        } catch (RuntimeException exception) {
            if (created) {
                dropDatabase(maintenanceUrl, username, password, databaseName);
            }
            throw exception;
        }
    }

    private TemporaryPostgresDatabase(String maintenanceUrl, String username, String password,
            String sourceDatabaseName, String databaseName, String databaseUrl) {
        this.maintenanceUrl = maintenanceUrl;
        this.username = username;
        this.password = password;
        this.sourceDatabaseName = sourceDatabaseName;
        this.databaseName = databaseName;
        this.databaseUrl = databaseUrl;
        this.shutdownHook = new Thread(this::closeQuietly, "drop-" + databaseName);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    String databaseUrl() {
        return databaseUrl;
    }

    String databaseName() {
        return databaseName;
    }

    String sourceDatabaseName() {
        return sourceDatabaseName;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        dropDatabase(maintenanceUrl, username, password, databaseName);
        closed = true;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down; this hook is the cleanup path.
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // Shutdown cannot report a cleanup failure back to the test runner.
        }
    }

    private static void dropDatabase(String maintenanceUrl, String username, String password, String databaseName) {
        requireOwnedName(databaseName);
        try (Connection connection = DriverManager.getConnection(maintenanceUrl, username, password)) {
            try (PreparedStatement terminate = connection.prepareStatement("""
                    SELECT pg_terminate_backend(pid)
                    FROM pg_stat_activity
                    WHERE datname = ? AND pid <> pg_backend_pid()
                    """)) {
                terminate.setString(1, databaseName);
                terminate.execute();
            }
            try (Statement drop = connection.createStatement()) {
                drop.execute("DROP DATABASE IF EXISTS " + quotedIdentifier(databaseName));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not drop the isolated PostgreSQL test database", exception);
        }
    }

    private static void requireOwnedName(String databaseName) {
        if (!databaseName.matches(DATABASE_PREFIX + "[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Refusing to manage an unowned PostgreSQL database");
        }
    }

    private static String quotedIdentifier(String identifier) {
        requireOwnedName(identifier);
        return '"' + identifier + '"';
    }

    private record JdbcUrl(String scheme, String rawAuthority, String databaseName, String rawQuery) {
        static JdbcUrl parse(String jdbcUrl) {
            Objects.requireNonNull(jdbcUrl, "APP_TEST_DATABASE_URL is required");
            if (!jdbcUrl.startsWith("jdbc:")) {
                throw new IllegalArgumentException("APP_TEST_DATABASE_URL must be a PostgreSQL JDBC URL");
            }
            try {
                URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
                String path = uri.getPath();
                if (!"postgresql".equals(uri.getScheme()) || uri.getRawAuthority() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null
                        || path == null || !path.matches("/[^/]+")) {
                    throw new IllegalArgumentException("APP_TEST_DATABASE_URL must name one maintenance database");
                }
                return new JdbcUrl(uri.getScheme(), uri.getRawAuthority(), path.substring(1), uri.getRawQuery());
            } catch (URISyntaxException exception) {
                throw new IllegalArgumentException("APP_TEST_DATABASE_URL is not a valid PostgreSQL JDBC URL", exception);
            }
        }

        String withDatabase(String databaseName) {
            requireOwnedName(databaseName);
            String query = rawQuery == null ? "" : "?" + rawQuery;
            return "jdbc:" + scheme + "://" + rawAuthority + "/" + databaseName + query;
        }
    }
}
