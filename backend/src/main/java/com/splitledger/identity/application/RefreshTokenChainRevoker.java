package com.splitledger.identity.application;

import com.splitledger.identity.domain.RefreshToken;
import com.splitledger.identity.infra.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every refresh token belonging to a user, in a transaction of its own.
 *
 * <p>This exists as a separate bean for one specific reason. Reuse of a rotated token is
 * detected inside {@code RefreshTokenService.rotate}, which then revokes the chain and
 * throws. Both happen inside the same {@code @Transactional} method, so the exception
 * rolls the revocation back and the chain survives — the attacker's remaining tokens stay
 * usable. An integration test caught exactly that.
 *
 * <p>{@code REQUIRES_NEW} makes the revocation commit independently of the caller's doomed
 * transaction. It has to live on a different bean because a self-invoked method bypasses
 * the Spring proxy and would silently inherit the outer transaction instead — the same
 * mechanism ADR-007 relies on for the idempotency marker.
 */
@Service
public class RefreshTokenChainRevoker {

    private final RefreshTokenRepository tokens;
    private final Clock clock = Clock.systemUTC();

    RefreshTokenChainRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeChain(UUID userId) {
        Instant now = clock.instant();
        for (RefreshToken token : tokens.findAllByUserId(userId)) {
            token.revoke(now);
            tokens.save(token);
        }
    }
}
