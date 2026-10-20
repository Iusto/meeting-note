package dev.aimeeting.api.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class RequestSecurityIntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    @Autowired MockMvc mvc;

    @Test
    void healthIsPublicButMeetingsRequireAuthentication() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/meetings")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void loginRequiresExactOriginAndIssuesStrictHttpOnlyCookie() throws Exception {
        mvc.perform(post("/api/v1/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"test-owner-token-00000000000000000\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));

        MvcResult login = mvc.perform(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"test-owner-token-00000000000000000\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn();

        String setCookie = login.getResponse().getHeader("Set-Cookie");
        String cookiePrefix = SessionService.COOKIE_NAME + "=";
        String value = setCookie.substring(cookiePrefix.length(), setCookie.indexOf(';'));
        mvc.perform(get("/api/v1/session").cookie(new Cookie(SessionService.COOKIE_NAME, value)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(post("/api/v1/meetings").cookie(new Cookie(SessionService.COOKIE_NAME, value))
                        .header("Origin", "http://evil.invalid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"blocked\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void forwardedHeadersCannotMakeLocalModeCookieSecure() throws Exception {
        mvc.perform(post("/api/v1/session").header("Origin", ORIGIN)
                        .header("Forwarded", "for=192.0.2.10;proto=https;host=evil.invalid")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "evil.invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"test-owner-token-00000000000000000\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", not(containsString("Secure"))));
    }
}
