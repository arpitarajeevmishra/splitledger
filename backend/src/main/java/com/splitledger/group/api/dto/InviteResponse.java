package com.splitledger.group.api.dto;

import com.splitledger.group.domain.GroupInvite;
import com.splitledger.group.domain.InviteStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * An invitation as its group's admins see it.
 *
 * <p>No token and no hash: the raw token is returned once, from the endpoint that creates
 * the invitation, and a listing that repeated it would turn "who have we invited?" into a
 * way to recover every live link.
 */
public record InviteResponse(
        UUID id,
        String email,
        InviteStatus status,
        Instant expiresAt,
        Instant createdAt) {

    public static InviteResponse of(GroupInvite invite, Instant now) {
        return new InviteResponse(invite.id(), invite.email(), invite.effectiveStatus(now),
                invite.expiresAt(), invite.createdAt());
    }
}
