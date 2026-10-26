package com.splitledger.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.splitledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "Explore the demo", end to end (SL-904).
 *
 * <p>The seed password is set here, which is what makes the whole feature exist: the
 * runner seeds at start-up, the service is conditional on the same value, and the config
 * endpoint derives its flag from it. Three things that must agree, and an integration test
 * is the only place they are all present at once.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-demo-tour-test-secret-long-enough-for-hs256",
        "app.demo.seed-password=a-demo-tour-test-password",
        "app.rate-limit.limits.demo-tour.capacity=1000"
})
class DemoTourIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void a_visitor_is_signed_in_with_no_credentials_at_all() throws Exception {
        mockMvc.perform(post("/api/v1/auth/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(DemoSeed.OWNER_EMAIL))
                // The refresh cookie comes with it, so a visitor browsing for longer than
                // the access token's life is not thrown out mid-tour.
                .andExpect(result -> {
                    String cookie = result.getResponse().getHeader("Set-Cookie");
                    if (cookie == null || !cookie.contains("refresh_token")) {
                        throw new AssertionError("no refresh cookie: " + cookie);
                    }
                    if (!cookie.contains("HttpOnly")) {
                        throw new AssertionError("refresh cookie is not HttpOnly: " + cookie);
                    }
                });
    }

    @Test
    void the_session_can_actually_see_the_seeded_group() throws Exception {
        String token = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/v1/auth/demo"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.accessToken");

        // The point of the tour. A session that signs in and lands on an empty state
        // would satisfy every assertion above and show a visitor nothing.
        mockMvc.perform(get("/api/v1/groups").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].memberCount").value(3));
    }

    @Test
    void the_config_endpoint_advertises_the_tour_so_the_button_is_offered() throws Exception {
        // Derived from the same property that creates the endpoint. If these two could
        // disagree, the button would either 404 or be hidden on a deployment that has one.
        mockMvc.perform(get("/api/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestTourEnabled").value(true));
    }
}
