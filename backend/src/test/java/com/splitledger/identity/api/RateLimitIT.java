package com.splitledger.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.splitledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Its own context with a deliberately tiny allowance, so the limit is reached in two
 * requests rather than by hammering the endpoint -- and so it cannot consume the quota
 * that {@link AuthFlowIT} depends on.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-rate-limit-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=2",
        "app.rate-limit.limits.login.per=PT1M",
        "app.rate-limit.limits.register.capacity=2",
        "app.rate-limit.limits.register.per=PT1H"
})
class RateLimitIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void refusesTheThirdLoginAttemptWithA429AndARetryAfter() throws Exception {
        String body = "{\"email\":\"limited@example.com\",\"password\":\"password123\"}";

        // The account does not exist, so these are 401s -- which is the point: a limit that
        // only counted successful logins would not slow a password-guessing attack at all.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void refusesTheThirdRegistrationFromOneAddress() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"reg" + i + "-" + System.nanoTime()
                                    + "@example.com\",\"name\":\"R\","
                                    + "\"password\":\"password123\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"blocked-" + System.nanoTime()
                                + "@example.com\",\"name\":\"R\","
                                + "\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
