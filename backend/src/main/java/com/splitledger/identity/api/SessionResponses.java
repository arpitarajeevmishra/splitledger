package com.splitledger.identity.api;

import com.splitledger.identity.api.dto.AuthResponse;
import com.splitledger.identity.api.dto.SelfProfileResponse;
import com.splitledger.identity.application.AuthService;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * One place that knows how a session is handed to a browser.
 *
 * <p>Extracted when a second endpoint needed to start one (SL-904). The alternative was
 * copying the cookie flags, and {@code HttpOnly}, {@code Secure}, {@code SameSite=Strict}
 * and the path scope are exactly the kind of settings that get copied once, changed in one
 * copy, and leave the other quietly weaker (ADR-011).
 */
@Component
public class SessionResponses {

    static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private final Duration refreshTtl;
    private final boolean cookieSecure;

    SessionResponses(@Value("${app.auth.refresh-ttl}") Duration refreshTtl,
            @Value("${app.auth.cookie-secure}") boolean cookieSecure) {
        this.refreshTtl = refreshTtl;
        this.cookieSecure = cookieSecure;
    }

    /**
     * A null refresh token means "keep the cookie you have", and the response must then
     * carry no {@code Set-Cookie} at all.
     *
     * <p>That case is the losing half of two tabs refreshing at once (O-9). The browser
     * already holds the live token its other tab was given; overwriting that cookie with
     * anything -- including a blank -- would take the session away from the tab that won.
     */
    public ResponseEntity<AuthResponse> respond(AuthService.Session session, HttpStatus status) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (session.refreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE,
                    refreshCookie(session.refreshToken()).toString());
        }
        return response.body(new AuthResponse(session.accessToken(), session.expiresInSeconds(),
                SelfProfileResponse.from(session.user())));
    }

    public ResponseCookie refreshCookie(String rawToken) {
        return base(REFRESH_COOKIE, rawToken).maxAge(refreshTtl).build();
    }

    public ResponseCookie expiredCookie() {
        return base(REFRESH_COOKIE, "").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                // Scoped to the auth endpoints, so the token is not attached to every
                // request and its exposure stays as narrow as the design allows.
                .path(REFRESH_PATH);
    }
}
