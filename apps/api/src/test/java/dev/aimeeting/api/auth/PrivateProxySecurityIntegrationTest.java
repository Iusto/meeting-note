package dev.aimeeting.api.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.allowed-origins=https://meeting-host.example.ts.net",
        "server.forward-headers-strategy=framework",
        "server.tomcat.redirect-context-root=false"
})
@AutoConfigureMockMvc
class PrivateProxySecurityIntegrationTest {
    private static final String ORIGIN = "https://meeting-host.example.ts.net";
    private static final String TOKEN = "test-owner-token-00000000000000000";

    @Autowired MockMvc mvc;

    @Test
    void trustedHttpsProxyIssuesAndClearsSecureCookie() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/session")
                        .header("Origin", ORIGIN)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "meeting-host.example.ts.net")
                        .header("X-Forwarded-Port", "443")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andReturn();

        String setCookie = login.getResponse().getHeader("Set-Cookie");
        String prefix = SessionService.COOKIE_NAME + "=";
        Cookie session = new Cookie(SessionService.COOKIE_NAME,
                setCookie.substring(prefix.length(), setCookie.indexOf(';')));

        mvc.perform(delete("/api/v1/session")
                        .cookie(session)
                        .header("Origin", ORIGIN)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "meeting-host.example.ts.net")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void unrelatedHttpsOriginIsRejected() throws Exception {
        mvc.perform(post("/api/v1/session")
                        .header("Origin", "https://other-host.example.ts.net")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "meeting-host.example.ts.net")
                        .header("X-Forwarded-Port", "443")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isForbidden());
    }
}
