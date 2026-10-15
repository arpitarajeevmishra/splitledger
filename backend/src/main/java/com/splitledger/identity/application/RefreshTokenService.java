package com.splitledger.identity.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.identity.domain.RefreshToken;
import com.splitledger.identity.infra.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh tokens, rotated on every use (ADR-011).
 *
 * <p>Only the SHA-256 hash is stored, so a database leak does not hand over live sessions.
 * Rotation forms a chain: using a token revokes it and records its replacement. Presenting
 * an already-revoked token therefore means the raw value leaked — so the entire chain for
 * that user is revoked, not just the link that was replayed.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final RefreshTokenChainRevoker chainRevoker;
    private final Duration refreshTtl;
    private final Clock clock;

    @Autowired
    RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenChainRevoker chainRevoker,
            @Value("${app.auth.refresh-ttl}") Duration refreshTtl) {
        this(tokens, chainRevoker, refreshTtl, Clock.systemUTC());
    }

    /** Visible for testing. */
    RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenChainRevoker chainRevoker,
            Duration refreshTtl, Clock clock) {
        this.tokens = tokens;
        this.chainRevoker = chainRevoker;
        this.refreshTtl = refreshTtl;
        this.clock = clock;
    }

    /** The raw token is returned once and never stored. */
    @Transactional
    public String issue(UUID userId) {
        Instant now = clock.instant();
        String raw = randomToken();
        tokens.save(RefreshToken.issue(userId, hash(raw), now.plus(refreshTtl)));
        return raw;
    }

    /**
     * Exchange a refresh token for a new one.
     *
     * @throws ApiException {@code REFRESH_TOKEN_INVALID} if unknown, expired, or already
     *     used — and in the reuse case the whole chain is revoked first
     */
    @Transactional
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken existing = tokens.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (existing.isRevoked()) {
            // Someone presented a token we already rotated away. Either it leaked or a
            // client is replaying; both mean every token in this chain is suspect.
            log.warn("Refresh token reuse detected for user {}; revoking the chain",
                    existing.userId());
            // Must commit independently: this method throws immediately below, and that
            // would roll a same-transaction revocation back. See RefreshTokenChainRevoker.
            chainRevoker.revokeChain(existing.userId());
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
                    "This refresh token has already been used");
        }
        if (!existing.isUsable(now)) {
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID, "Refresh token has expired");
        }

        String replacementRaw = randomToken();
        RefreshToken replacement =
                RefreshToken.issue(existing.userId(), hash(replacementRaw), now.plus(refreshTtl));
        tokens.save(replacement);
        existing.rotateTo(replacement.id(), now);
        tokens.save(existing);
        return new Rotation(existing.userId(), replacementRaw);
    }

    /** Ordinary logout: the caller's transaction commits, so no special propagation. */
    @Transactional
    public void revokeAll(UUID userId) {
        chainRevoker.revokeChain(userId);
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    public record Rotation(UUID userId, String rawToken) {
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex is 64 characters, which is exactly the column width. */
    static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }
}
