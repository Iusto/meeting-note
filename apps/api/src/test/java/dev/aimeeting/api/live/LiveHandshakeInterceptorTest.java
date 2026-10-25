package dev.aimeeting.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.config.AppProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.socket.WebSocketHandler;

class LiveHandshakeInterceptorTest {
    @Test
    void requiresBothAllowedOriginAndAuthenticatedCookie() {
        AppProperties properties = new AppProperties("a-valid-owner-token-000000000000", List.of("http://localhost:5173"), Path.of("target"), false, 100);
        SessionService sessions = sessionService(properties);
        String sessionId = sessions.login("a-valid-owner-token-000000000000");
        LiveHandshakeInterceptor interceptor = new LiveHandshakeInterceptor(sessions, properties);
        WebSocketHandler handler = mock(WebSocketHandler.class);

        ServerHttpRequest allowed = request("http://localhost:5173", sessionId);
        ServerHttpRequest rejected = request("http://evil.invalid", sessionId);

        assertThat(interceptor.beforeHandshake(allowed, mock(ServerHttpResponse.class), handler, new java.util.HashMap<>())).isTrue();
        assertThat(interceptor.beforeHandshake(rejected, mock(ServerHttpResponse.class), handler, new java.util.HashMap<>())).isFalse();
    }

    private SessionService sessionService(AppProperties properties) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE runtime_state(id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO runtime_state(id) VALUES (1)");
        jdbc.execute("CREATE TABLE owner_account(id SMALLINT PRIMARY KEY CHECK(id=1),username VARCHAR(64) UNIQUE NOT NULL,password_salt VARCHAR(64) NOT NULL,password_hash VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE auth_sessions(id_hash VARCHAR(64) PRIMARY KEY,expires_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        return new SessionService(properties, jdbc);
    }

    private ServerHttpRequest request(String origin, String sessionId) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.add(HttpHeaders.COOKIE, SessionService.COOKIE_NAME + "=" + sessionId);
        org.mockito.Mockito.when(request.getHeaders()).thenReturn(headers);
        return request;
    }
}
