package dev.aimeeting.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.aimeeting.api.config.AppProperties;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:owner-auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OwnerAuthenticationIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String TOKEN = "test-owner-token-00000000000000000";
    private static final String USERNAME = "Owner.Name";
    private static final String PASSWORD = "a-secure-owner-password";
    private final AtomicInteger addresses = new AtomicInteger(10);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppProperties properties;

    @Test
    void oneTimeSetupAndDurableCredentialSessions() throws Exception {
        mvc.perform(get("/api/v1/session/configuration"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupRequired").value(true));
        mvc.perform(post("/api/v1/session/setup").contentType(MediaType.APPLICATION_JSON).content(setupJson(TOKEN)))
                .andExpect(status().isForbidden());
        mvc.perform(from(post("/api/v1/session/setup").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(setupJson("wrong-owner-token"))))
                .andExpect(status().isUnauthorized());

        MvcResult legacy = mvc.perform(from(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + TOKEN + "\"}")))
                .andExpect(status().isOk()).andReturn();
        Cookie legacyCookie = cookie(legacy);

        CompletableFuture<MvcResult> setup = CompletableFuture.supplyAsync(this::setupRequest);
        CompletableFuture<MvcResult> racingLegacy = CompletableFuture.supplyAsync(this::legacyLoginRequest);
        assertThat(setup.join().getResponse().getStatus()).isEqualTo(200);
        MvcResult racingLegacyResult = racingLegacy.join();
        assertThat(racingLegacyResult.getResponse().getStatus()).isIn(200, 401);
        if (racingLegacyResult.getResponse().getStatus() == 200) {
            mvc.perform(get("/api/v1/session").cookie(cookie(racingLegacyResult))).andExpect(status().isUnauthorized());
        }
        mvc.perform(from(post("/api/v1/session/setup").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(setupJson(TOKEN))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_ALREADY_CONFIGURED"));

        mvc.perform(get("/api/v1/session/configuration"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupRequired").value(false));
        mvc.perform(get("/api/v1/session").cookie(legacyCookie)).andExpect(status().isUnauthorized());
        mvc.perform(from(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + TOKEN + "\"}")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid credentials"));

        String storedUsername = jdbc.queryForObject("SELECT username FROM owner_account WHERE id=1", String.class);
        String salt = jdbc.queryForObject("SELECT password_salt FROM owner_account WHERE id=1", String.class);
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM owner_account WHERE id=1", String.class);
        assertThat(storedUsername).isEqualTo("owner.name");
        assertThat(salt).doesNotContain(PASSWORD).doesNotContain(TOKEN);
        assertThat(passwordHash).doesNotContain(PASSWORD).doesNotContain(TOKEN);

        assertGenericUnauthorized("missing-owner", PASSWORD);
        assertGenericUnauthorized(USERNAME, "wrong-password-value");

        MvcResult remembered = mvc.perform(from(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson(true))))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=2592000")))
                .andReturn();
        Cookie rememberedCookie = cookie(remembered);
        String rawSessionId = rememberedCookie.getValue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE id_hash=?", Integer.class, rawSessionId)).isZero();

        SessionService restarted = new SessionService(properties, jdbc);
        assertThat(restarted.isValid(rawSessionId)).isTrue();

        MvcResult replacement = mvc.perform(from(post("/api/v1/session").header("Origin", ORIGIN)
                        .cookie(rememberedCookie).contentType(MediaType.APPLICATION_JSON).content(loginJson(false))))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=43200")))
                .andReturn();
        Cookie replacementCookie = cookie(replacement);
        assertThat(restarted.isValid(rawSessionId)).isFalse();
        assertThat(restarted.isValid(replacementCookie.getValue())).isTrue();

        jdbc.update("UPDATE auth_sessions SET expires_at=?", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        mvc.perform(get("/api/v1/session").cookie(replacementCookie)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).isZero();

        MvcResult logoutLogin = mvc.perform(from(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson(false))))
                .andExpect(status().isOk()).andReturn();
        Cookie logoutCookie = cookie(logoutLogin);
        mvc.perform(from(delete("/api/v1/session").header("Origin", ORIGIN).cookie(logoutCookie)))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(restarted.isValid(logoutCookie.getValue())).isFalse();
    }

    private MvcResult setupRequest() {
        try {
            return mvc.perform(from(post("/api/v1/session/setup").header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON).content(setupJson(TOKEN))))
                    .andReturn();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MvcResult legacyLoginRequest() {
        try {
            return mvc.perform(from(post("/api/v1/session").header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + TOKEN + "\"}")))
                    .andReturn();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertGenericUnauthorized(String username, String password) throws Exception {
        mvc.perform(from(post("/api/v1/session").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"rememberMe\":false}")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request) {
        int suffix = addresses.getAndIncrement();
        return request.with(mock -> { mock.setRemoteAddr("192.0.2." + suffix); return mock; });
    }

    private static Cookie cookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        String prefix = SessionService.COOKIE_NAME + "=";
        return new Cookie(SessionService.COOKIE_NAME, header.substring(prefix.length(), header.indexOf(';')));
    }

    private static String setupJson(String token) {
        return "{\"token\":\"" + token + "\",\"username\":\"" + USERNAME
                + "\",\"password\":\"" + PASSWORD + "\",\"rememberMe\":false}";
    }

    private static String loginJson(boolean rememberMe) {
        return "{\"username\":\"owner.name\",\"password\":\"" + PASSWORD
                + "\",\"rememberMe\":" + rememberMe + "}";
    }
}
