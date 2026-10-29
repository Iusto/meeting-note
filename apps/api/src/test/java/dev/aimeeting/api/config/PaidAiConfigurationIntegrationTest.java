package dev.aimeeting.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aimeeting.api.auth.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Dummy keys exercise activation and serialization only; no provider operation is invoked. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paid-capabilities;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.paid-api-tests-enabled=true", "app.deepgram.startup-recovery-enabled=false",
        "DEEPGRAM_API_KEY=dummy-deepgram-secret", "OPENAI_API_KEY=dummy-openai-secret"
})
@AutoConfigureMockMvc
class PaidAiConfigurationIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void capabilitiesRequireLoginAndNeverSerializeSecretsWhileUsageReflectsDeployment() throws Exception {
        mvc.perform(get("/api/v1/ai/configuration")).andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/v1/session").header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"test-owner-token-00000000000000000\"}"))
                .andExpect(status().isOk()).andReturn();
        String header = login.getResponse().getHeader("Set-Cookie");
        Cookie cookie = new Cookie(SessionService.COOKIE_NAME,
                header.substring((SessionService.COOKIE_NAME + "=").length(), header.indexOf(';')));
        var response = mvc.perform(get("/api/v1/ai/configuration").cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.speechAvailable").value(true))
                .andExpect(jsonPath("$.llmAvailable").value(true))
                .andExpect(jsonPath("$.maximumMeetingUsd").value(3.0)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("dummy-", "API_KEY", "apiKey");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/settings")
                        .cookie(cookie).header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyBudgetUsd\":10,\"perMeetingBudgetUsd\":8,\"warnAtPercent\":80,\"perMeetingMaxMinutes\":240,\"revision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.perMeetingBudgetUsd").value(8.0));
        mvc.perform(get("/api/v1/ai/configuration").cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maximumMeetingUsd").value(8.0));
        mvc.perform(get("/api/v1/settings").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paidApiEnabled").value(true));
        mvc.perform(get("/api/v1/usage?month=2026-10").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paidApiEnabled").value(true));
    }
}
