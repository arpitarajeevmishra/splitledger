package com.splitledger.identity.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies short-lived access tokens (ADR-011). The token is returned in the
 * response body and held in memory by the browser; it is never written to a cookie or to
 * localStorage.
 *
 * <p>There is no revocation list. Logout revokes the refresh chain, so a stolen access
 * token is valid for at most its TTL — an accepted trade-off that keeps every request
 * free of a lookup.
 *
 * <p>This class is the only place {@code java.util.Date} appears: jjwt's builder requires
 * it, and the conversion is confined here rather than allowed to spread. The ArchUnit
 * rule banning legacy date types excludes this class by name for that reason.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Clock clock;

    @Autowired
    JwtService(@Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl}") Duration accessTtl) {
        this(secret, accessTtl, Clock.systemUTC());
    }

    /** Visible for testing: a fixed clock makes expiry assertions deterministic. */
    JwtService(String secret, Duration accessTtl, Clock clock) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            // Keys.hmacShaKeyFor would throw anyway; failing here says why.
            throw new IllegalArgumentException(
                    "app.jwt.secret must be at least 32 bytes for HS256, was " + secretBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTtl = accessTtl;
        this.clock = clock;
    }

    public String issueAccessToken(UUID userId, String email) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * @return the user id from a token whose signature and expiry both check out
     * @throws ApiException {@code UNAUTHENTICATED} for anything else — a tampered
     *     signature, an expired token and a malformed string are indistinguishable to
     *     the caller on purpose
     */
    public UUID verifyAndExtractUserId(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return UUID.fromString(subject);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Access token is not valid");
        }
    }

    public Duration accessTtl() {
        return accessTtl;
    }
}
