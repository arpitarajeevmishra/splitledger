package com.splitledger.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A one-time proof that somebody can read a mailbox (SL-901).
 *
 * <p>Only the SHA-256 hash of the token is stored, like every other token in this schema:
 * the raw value goes into one email and is never written down, so a database leak yields
 * nothing anyone can redeem.
 *
 * <p>Single-use and short-lived are separate properties and both matter. Expiry bounds how
 * long a link left sitting in an inbox stays dangerous; single-use bounds what a link
 * already used is worth to whoever finds it later.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailVerificationToken() {
        // Hibernate.
    }

    private EmailVerificationToken(UUID id, UUID userId, String tokenHash, Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static EmailVerificationToken issue(UUID userId, String tokenHash, Instant now,
            Instant expiresAt) {
        return new EmailVerificationToken(UUID.randomUUID(), userId, tokenHash, now, expiresAt);
    }

    /** Unused and unexpired. Both halves, because either alone is a different bug. */
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void use(Instant at) {
        usedAt = at;
    }

    public UUID userId() {
        return userId;
    }

    public Instant usedAt() {
        return usedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
