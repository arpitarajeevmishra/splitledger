package com.splitledger.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The whole session lifecycle against a real PostgreSQL. Named *IT so it runs under
 * Failsafe in CI, where Docker exists (ADR-012).
 *
 * <p>MockMvc rather than a live server: the security filter chain still applies, so the
 * cookie and problem+json behaviour under test is real, and it avoids pulling in the
 * spring-boot-restclient module that TestRestTemplate now requires in Boot 4.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=an-integration-test-secret-long-enough-for-hs256",
        "app.auth.cookie-secure=false",
        // This test registers and logs in several times from one address. Rate limiting has
        // its own test; leaving the production allowance here would couple the two and make
        // an unrelated assertion fail as the suite grew.
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class AuthFlowIT {

    @Autowired
    private MockMvc mockMvc;

    private static String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"name\":\"Rahul\",\"password\":\"password123\"}";
    }

    @Test
    void registerLoginRefreshReuseAndLogout() throws Exception {
        String email = "flow-" + System.nanoTime() + "@example.com";

        // --- register ---
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                // The refresh token must never appear in the body, only as a cookie.
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();

        Cookie refresh = registered.getResponse().getCookie("refresh_token");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/v1/auth");
        assertThat(registered.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");

        // --- a duplicate email is a 409 in the problem+json contract ---
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        // --- a password under the minimum is rejected by validation ---
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"short@example.com\",\"name\":\"A\","
                                + "\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // --- login ---
        MvcResult loggedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken =
                JsonPath.read(loggedIn.getResponse().getContentAsString(), "$.accessToken");
        Cookie loginCookie = loggedIn.getResponse().getCookie("refresh_token");
        assertThat(loginCookie).isNotNull();

        // --- a wrong password is a 401, same contract ---
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-one\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // --- a protected route rejects an anonymous caller as problem+json ---
        mockMvc.perform(get("/api/v1/groups"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        // --- and accepts the access token ---
        // This asserted 404 when /groups did not exist yet, using "route missing" as a
        // proxy for "authentication passed". SL-201 created the route and the test broke.
        // Asserting the real response instead: an authenticated new user has no groups.
        mockMvc.perform(get("/api/v1/groups").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // --- refresh rotates the cookie ---
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", loginCookie.getValue())))
                .andExpect(status().isOk())
                .andReturn();
        Cookie rotated = refreshed.getResponse().getCookie("refresh_token");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(loginCookie.getValue());

        // --- the same cookie again, moments later, is the other tab, not a thief (O-9) ---
        // Two tabs are separate JavaScript contexts sharing one cookie, so both send the
        // value that was live when their page loaded and nothing on the client can stop
        // them. The loser gets an access token and deliberately no Set-Cookie: the browser
        // must keep the token the winning tab stored.
        MvcResult raced = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", loginCookie.getValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        assertThat(raced.getResponse().getCookie("refresh_token")).isNull();

        // --- and the winner's cookie is still live, which is the whole point ---
        MvcResult refreshedAgain = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", rotated.getValue())))
                .andExpect(status().isOk())
                .andReturn();
        Cookie current = refreshedAgain.getResponse().getCookie("refresh_token");
        assertThat(current).isNotNull();

        // --- replaying a cookie whose successor is already dead is read as a leak ---
        // The grace window only ever covers the live tip of the chain. The login cookie's
        // replacement has since been rotated away, so this is reuse and every token for
        // the user goes -- including the one this session is holding right now.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", loginCookie.getValue())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", current.getValue())))
                .andExpect(status().isUnauthorized());

        // --- the profile endpoints, which take no user id in the path ---
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Rahul"))
                // Present and null, not absent: the field is serialised so a client can see
                // it exists and is empty, which matters for a PATCH-able resource.
                .andExpect(jsonPath("$.upiId").isEmpty());

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upiId\":\"rahul@oksbi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upiId").value("rahul@oksbi"))
                // name was not supplied, so it must be untouched
                .andExpect(jsonPath("$.name").value("Rahul"));

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upiId\":\"not-a-vpa\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // --- logout is idempotent even with no live session ---
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("refresh_token", rotated.getValue())))
                .andExpect(status().isNoContent());
    }
}
