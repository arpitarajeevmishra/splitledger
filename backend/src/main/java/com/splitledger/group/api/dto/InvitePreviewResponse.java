package com.splitledger.group.api.dto;

import java.time.Instant;

/**
 * What a landing page may show before the recipient has signed in: which group, and which
 * address to sign in with. Nothing about the group's members or money -- holding the link
 * is not membership.
 */
public record InvitePreviewResponse(String groupName, String email, Instant expiresAt) {
}
