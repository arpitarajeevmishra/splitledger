package com.splitledger.identity.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, credential verification, session refresh and logout. */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final boolean registrationEnabled;
    private final Clock clock;

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            RefreshTokenService refreshTokens,
            @Value("${app.registration-enabled}") boolean registrationEnabled) {
        this(users, passwordEncoder, jwtService, refreshTokens, registrationEnabled,
                Clock.systemUTC());
    }

    /** Visible for testing. */
    AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            RefreshTokenService refreshTokens, boolean registrationEnabled, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.registrationEnabled = registrationEnabled;
        this.clock = clock;
    }

    @Transactional
    public Session register(String email, String name, String rawPassword) {
        if (!registrationEnabled) {
            // The app can be deployed before email verification ships without exposing
            // the signup hole that verification closes (ADR-013, SL-901).
            throw new ApiException(ErrorCode.REGISTRATION_DISABLED,
                    "Registration is not open yet");
        }
        String normalised = User.normaliseEmail(email);
        if (users.existsByEmail(normalised)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "An account already exists for that email");
        }
        User user = users.save(User.register(normalised, name.trim(),
                passwordEncoder.encode(rawPassword), Instant.now(clock)));
        return startSession(user);
    }

    @Transactional
    public Session login(String email, String rawPassword) {
        User user = users.findByEmail(User.normaliseEmail(email))
                .orElse(null);

        // Verify a hash even when the user is absent, so a missing account and a wrong
        // password take indistinguishable time. Without this, response timing tells an
        // attacker which addresses are registered.
        String hashToCheck = user != null ? user.passwordHash() : DUMMY_HASH;
        boolean matches = passwordEncoder.matches(rawPassword, hashToCheck);

        if (user == null || !matches) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS,
                    "Email or password is incorrect");
        }
        return startSession(user);
    }

    @Transactional
    public Session refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        User user = users.findById(rotation.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));
        return new Session(jwtService.issueAccessToken(user.id(), user.email()),
                jwtService.accessTtl().toSeconds(), rotation.rawToken(), user);
    }

    /** Idempotent: logging out twice, or with no session, is a success. */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        try {
            RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
            refreshTokens.revokeAll(rotation.userId());
        } catch (ApiException ex) {
            // An unknown or already-used token means there is no session to end.
        }
    }

    private Session startSession(User user) {
        return new Session(jwtService.issueAccessToken(user.id(), user.email()),
                jwtService.accessTtl().toSeconds(), refreshTokens.issue(user.id()), user);
    }

    public record Session(String accessToken, long expiresInSeconds, String refreshToken,
            User user) {
    }

    /**
     * A real BCrypt hash of a value nothing can match, used only to spend the same time
     * verifying a password for an address that does not exist.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    public UUID currentUserId(Object principal) {
        if (principal instanceof UUID userId) {
            return userId;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
}
