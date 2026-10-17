package com.splitledger.identity.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: a {@code null} field is left unchanged, so a client can send only what
 * it is editing.
 *
 * <p>There is deliberately no {@code email} or {@code password} component. Changing either
 * needs its own flow — re-verification for one, the current password for the other — and
 * leaving them out means no amount of carelessness in a service can let this endpoint
 * change them.
 *
 * @param name     new display name, or null to leave it
 * @param upiId    new UPI address, blank to clear it, or null to leave it
 */
public record UpdateProfileRequest(
        @Size(min = 1, max = 100) String name,

        // A VPA is handle@psp. Loose on purpose: PSP handles change often and a strict
        // allow-list would reject valid addresses, which is worse than storing an odd one
        // — nothing is paid out from this field, it is shown to a human who then pays.
        @Size(max = 100)
        @Pattern(regexp = "^$|^[a-zA-Z0-9.\\-_]{2,70}@[a-zA-Z][a-zA-Z0-9.\\-]{1,28}$",
                message = "must look like handle@psp")
        String upiId) {
}
