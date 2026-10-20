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
}
