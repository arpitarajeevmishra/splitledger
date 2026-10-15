package com.splitledger.identity.api;

import com.splitledger.identity.api.dto.AuthResponse;
import com.splitledger.identity.api.dto.LoginRequest;
import com.splitledger.identity.api.dto.RegisterRequest;
import com.splitledger.identity.api.dto.UserResponse;
import com.splitledger.identity.application.AuthService;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. The refresh token is only ever set and read as a cookie — it
 * is never accepted in a body or returned in one, so a cross-site script cannot reach it
 * (ADR-011).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final Duration refreshTtl;
    private final boolean cookieSecure;

    AuthController(AuthService authService,
            @Value("${app.auth.refresh-ttl}") Duration refreshTtl,
            @Value("${app.auth.cookie-secure}") boolean cookieSecure) {
        this.authService = authService;
        this.refreshTtl = refreshTtl;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.Session session =
                authService.register(request.email(), request.name(), request.password());
        return respond(session, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return respond(authService.login(request.email(), request.password()), HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return respond(authService.refresh(refreshToken == null ? "" : refreshToken),
                HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .build();
    }

    private ResponseEntity<AuthResponse> respond(AuthService.Session session, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(new AuthResponse(session.accessToken(), session.expiresInSeconds(),
                        UserResponse.from(session.user())));
    }

    private ResponseCookie refreshCookie(String rawToken) {
        return ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                // Scoped to the auth endpoints, so the token is not attached to every
                // request and its exposure stays as narrow as the design allows.
                .path(REFRESH_PATH)
                .maxAge(refreshTtl)
                .build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(REFRESH_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }
}
