package com.splitledger.group.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/**
 * An invitation to join a group.
 *
 * <p>Only the token's hash is stored; the raw value exists in the link and nowhere else.
 *
 * <p>Expiry is evaluated on use, never written: an invite past its date is unusable the
 * moment anyone tries it, so a sweeper job would only be relabelling rows nobody can
 * redeem. {@link #isUsable} is the single authority on redemption and
 * {@link #effectiveStatus} is the label to show a human -- which means reading an invite
 * never writes to it, and a listing cannot disagree with what redeeming it would do.
 */
@Entity
@Table(name = "group_invites")
public class GroupInvite {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "email", nullable = false, updatable = false, length = 254)
    private String email;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private InviteStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected GroupInvite() {
        // for JPA
    }

    private GroupInvite(UUID id, UUID groupId, String email, UUID invitedBy, String tokenHash,
            Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.groupId = groupId;
        this.email = email;
        this.invitedBy = invitedBy;
        this.tokenHash = tokenHash;
        this.status = InviteStatus.PENDING;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static GroupInvite issue(UUID groupId, String email, UUID invitedBy, String tokenHash,
            Instant now, Instant expiresAt) {
        // Lower-cased to match the partial unique index on (group_id, lower(email)), so
        // "A@x.com" and "a@x.com" cannot both hold an open invite to the same group.
        return new GroupInvite(UUID.randomUUID(), groupId, email.trim().toLowerCase(), invitedBy,
                tokenHash, now, expiresAt);
    }

    public boolean isUsable(Instant now) {
        return status == InviteStatus.PENDING && now.isBefore(expiresAt);
    }

    /**
     * The status to display. EXPIRED is derived rather than stored, so a pending invite
     * that has run out reads as expired without anyone having had to write to it.
     */
    public InviteStatus effectiveStatus(Instant now) {
        if (status == InviteStatus.PENDING && !now.isBefore(expiresAt)) {
            return InviteStatus.EXPIRED;
        }
        return status;
    }

    /**
     * Compares against an already-hashed token. Takes the hash rather than the raw value
     * so the domain does no hashing, and compares rather than exposing, so there is no
     * accessor that could put a stored hash into a response by accident.
     */
    public boolean matchesToken(String candidateHash) {
        return MessageDigest.isEqual(tokenHash.getBytes(StandardCharsets.UTF_8),
                candidateHash.getBytes(StandardCharsets.UTF_8));
    }

    public void accept(Instant now) {
        this.status = InviteStatus.ACCEPTED;
        this.respondedAt = now;
    }

    public void decline(Instant now) {
        this.status = InviteStatus.DECLINED;
        this.respondedAt = now;
    }

    public void revoke(Instant now) {
        this.status = InviteStatus.REVOKED;
        this.respondedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID groupId() {
        return groupId;
    }

    public String email() {
        return email;
    }

    public UUID invitedBy() {
        return invitedBy;
    }

    public InviteStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
