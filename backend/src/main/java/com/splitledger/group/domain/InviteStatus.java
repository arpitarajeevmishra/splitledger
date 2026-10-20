package com.splitledger.group.domain;

/** Matches the CHECK constraint on group_invites.status. */
public enum InviteStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    REVOKED
}
