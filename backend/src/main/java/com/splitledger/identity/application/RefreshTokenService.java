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
    private final Duration refreshGrace;
    private final Clock clock;

    @Autowired
    RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenChainRevoker chainRevoker,
            @Value("${app.auth.refresh-ttl}") Duration refreshTtl,
            @Value("${app.auth.refresh-grace}") Duration refreshGrace) {
        this(tokens, chainRevoker, refreshTtl, refreshGrace, Clock.systemUTC());
    }

    /** Visible for testing. */
    RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenChainRevoker chainRevoker,
            Duration refreshTtl, Duration refreshGrace, Clock clock) {
        this.tokens = tokens;
        this.chainRevoker = chainRevoker;
        this.refreshTtl = refreshTtl;
        this.refreshGrace = refreshGrace;
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
            if (supersededMomentsAgo(existing, now)) {
                // Two tabs refreshed at once (O-9). They are separate JavaScript contexts
                // sharing one cookie, so no client-side guard can stop them, and treating
                // the loser as a leak logged out a user who did nothing wrong.
                log.debug("Concurrent refresh for user {}; serving an access token without "
                        + "rotating", existing.userId());
                return new Rotation(existing.userId(), null);
            }
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

    /**
     * Whether this is the losing half of a race rather than a replayed leak (O-9).
     *
     * <p>Three conditions, and each rules out a different thing:
     *
     * <ul>
     *   <li><b>It was rotated, not revoked.</b> {@code replacedBy} is null when a token was
     *       killed by logout or by a chain revocation, and replaying one of those is never
     *       benign -- forgiving it would make logout reversible for anyone holding the old
     *       cookie.
     *   <li><b>It was rotated moments ago.</b> A leaked token replayed next week is an
     *       attack; one replayed inside the window is the other tab's request arriving.
     *   <li><b>Its successor is still live.</b> If the chain has since been revoked, the
     *       revocation wins. This is what stops the window from resurrecting a session
     *       somebody deliberately ended.
     * </ul>
     *
     * <p>What the caller gets is deliberately asymmetric: an access token, and <b>no new
     * refresh token</b>. A thief replaying inside the window therefore gains fifteen
     * minutes and no way to extend them, while the legitimate browser keeps the live
     * cookie its other tab already stored. Full forgiveness -- handing out a fresh refresh
     * token -- would turn a bounded window into a foothold.
     */
    private boolean supersededMomentsAgo(RefreshToken replayed, Instant now) {
        Instant rotatedAt = replayed.revokedAt();
        if (replayed.replacedBy() == null || rotatedAt == null
                || rotatedAt.plus(refreshGrace).isBefore(now)) {
            return false;
        }
        return tokens.findById(replayed.replacedBy())
                .filter(successor -> successor.isUsable(now))
                .isPresent();
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

    /**
     * @param rawToken the replacement, or <b>null</b> meaning "keep the one you have":
     *     the caller lost a race with another tab and the cookie in the browser is
     *     already the live token (O-9)
     */
    public record Rotation(UUID userId, String rawToken) {
    }


}
