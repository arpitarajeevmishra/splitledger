package com.splitledger.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The whole session lifecycle against a real PostgreSQL. Named *IT so it runs under
 * Failsafe in CI, where Docker exists (ADR-012).
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=an-integration-test-secret-long-enough-for-hs256",
        "app.auth.cookie-secure=false"
})
class AuthFlowIT {

    @Autowired
    private TestRestTemplate rest;

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<String> jsonWithCookie(String body, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, cookie);
        return new HttpEntity<>(body, headers);
    }

    private static String refreshCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        String cookie = cookies.stream()
                .filter(c -> c.startsWith(AuthController.REFRESH_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        // Only the name=value pair goes back on the request.
        return cookie.split(";", 2)[0];
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerLoginRefreshReuseAndLogout() {
        String email = "flow-" + System.nanoTime() + "@example.com";

        // --- register ---
        ResponseEntity<Map> registered = rest.postForEntity("/api/v1/auth/register",
                json("{\"email\":\"" + email + "\",\"name\":\"Rahul\","
                        + "\"password\":\"password123\"}"), Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).containsKey("accessToken");
        // The refresh token must never appear in the body, only as a cookie.
        assertThat(registered.getBody()).doesNotContainKey("refreshToken");

        String setCookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");

        // --- a duplicate email is a 409 ---
        ResponseEntity<Map> duplicate = rest.postForEntity("/api/v1/auth/register",
                json("{\"email\":\"" + email + "\",\"name\":\"Rahul\","
                        + "\"password\":\"password123\"}"), Map.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("code", "EMAIL_ALREADY_REGISTERED");

        // --- login ---
        ResponseEntity<Map> loggedIn = rest.postForEntity("/api/v1/auth/login",
                json("{\"email\":\"" + email + "\",\"password\":\"password123\"}"),
                Map.class);
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) loggedIn.getBody().get("accessToken");
        String cookie = refreshCookie(loggedIn);

        // --- a wrong password is a 401 in the problem+json contract ---
        ResponseEntity<Map> wrong = rest.postForEntity("/api/v1/auth/login",
                json("{\"email\":\"" + email + "\",\"password\":\"not-the-password\"}"),
                Map.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody()).containsEntry("code", "INVALID_CREDENTIALS");

        // --- a protected route rejects an anonymous caller as problem+json ---
        ResponseEntity<Map> anonymous = rest.exchange("/api/v1/groups", HttpMethod.GET,
                HttpEntity.EMPTY, Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).containsEntry("code", "UNAUTHENTICATED");

        // --- and accepts the access token: 404 means authentication passed ---
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(accessToken);
        ResponseEntity<String> authenticated = rest.exchange("/api/v1/groups", HttpMethod.GET,
                new HttpEntity<>(bearer), String.class);
        assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // --- refresh rotates the cookie ---
        ResponseEntity<Map> refreshed = rest.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                jsonWithCookie("", cookie), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedCookie = refreshCookie(refreshed);
        assertThat(rotatedCookie).isNotEqualTo(cookie);

        // --- replaying the old cookie is rejected: reuse means it leaked ---
        ResponseEntity<Map> replayed = rest.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                jsonWithCookie("", cookie), Map.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replayed.getBody()).containsEntry("code", "REFRESH_TOKEN_INVALID");

        // --- and the rotated one is dead too, because the whole chain was revoked ---
        ResponseEntity<Map> afterChainRevoked = rest.exchange("/api/v1/auth/refresh",
                HttpMethod.POST, jsonWithCookie("", rotatedCookie), Map.class);
        assertThat(afterChainRevoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // --- logout is idempotent even with no live session ---
        ResponseEntity<Void> loggedOut = rest.exchange("/api/v1/auth/logout", HttpMethod.POST,
                jsonWithCookie("", rotatedCookie), Void.class);
        assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
