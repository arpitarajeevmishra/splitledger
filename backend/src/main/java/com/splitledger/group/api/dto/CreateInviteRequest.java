package com.splitledger.group.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email where the invitation is addressed; it need not belong to a registered
 *     user, and the invited person must sign in with this address to accept
 */
public record CreateInviteRequest(
        @NotBlank @Email @Size(max = 254) String email) {

    /**
     * Trimmed before validation, not after.
     *
     * <p>Jakarta's {@code @Email} rejects surrounding whitespace, so a pasted
     * "bob@example.com " would be a 400 complaining the address is malformed -- which it
     * is not, and the person pasting it cannot see the difference. The compact
     * constructor runs as Jackson builds the record, so the constraint sees the trimmed
     * value.
     *
     * <p>Case is deliberately left alone here; {@code InviteService} lowercases it, which
     * is where it matters for matching the partial unique index.
     */
    public CreateInviteRequest {
        email = email == null ? null : email.trim();
    }
}
