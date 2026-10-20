package com.splitledger.group.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Membership of a group.
 *
 * <p>Departure sets {@code left_at} rather than deleting the row: the primary key is
 * (group_id, user_id), so a deleted-then-re-added member would collide, and the ledger
 * keeps referring to them regardless. Re-joining clears {@code left_at} on the same row
 * (finding C22).
 */
@Entity
@Table(name = "group_members")
public class GroupMember {

    @EmbeddedId
    private GroupMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private Role role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    protected GroupMember() {
        // for JPA
    }

    private GroupMember(GroupMemberId id, Role role, Instant joinedAt) {
        this.id = id;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public static GroupMember join(UUID groupId, UUID userId, Role role, Instant now) {
        return new GroupMember(new GroupMemberId(groupId, userId), role, now);
    }

    public boolean isActive() {
        return leftAt == null;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public void leave(Instant now) {
        this.leftAt = now;
    }

    /** Re-joining reuses the row; the primary key forbids a second one. */
    public void rejoin(Role newRole, Instant now) {
        this.leftAt = null;
        this.role = newRole;
        this.joinedAt = now;
    }

    public GroupMemberId id() {
        return id;
    }

    public UUID groupId() {
        return id.groupId();
    }

    public UUID userId() {
        return id.userId();
    }

    public Role role() {
        return role;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public Instant leftAt() {
        return leftAt;
    }
}
