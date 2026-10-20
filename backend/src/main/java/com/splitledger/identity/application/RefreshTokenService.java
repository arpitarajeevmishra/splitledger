package com.splitledger.identity.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.security.SecureTokens;
import com.splitledger.identity.domain.RefreshToken;
import com.splitledger.identity.infra.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
        String raw = SecureTokens.random();
        tokens.save(RefreshToken.issue(userId, SecureTokens.hash(raw), now.plus(refreshTtl)));
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
        RefreshToken existing = tokens.findByTokenHash(SecureTokens.hash(rawToken))
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

        String replacementRaw = SecureTokens.random();
        RefreshToken replacement =
                RefreshToken.issue(existing.userId(), SecureTokens.hash(replacementRaw), now.plus(refreshTtl));
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

    /**
     * End the session a raw token belongs to, if any. Deliberately does not rotate and
     * deliberately never throws: an unknown or already-used token simply means there is
     * no session left to end, and throwing would mark the caller's transaction
     * rollback-only even if the caller caught it.
     */
    @Transactional
    public void revokeSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(SecureTokens.hash(rawToken))
                .ifPresent(token -> chainRevoker.revokeChain(token.userId()));
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    public record Rotation(UUID userId, String rawToken) {
    }


}
