package com.splitledger.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 1, max = 100) String name,
        // Minimum 8 per LLD section 12. The maximum is not arbitrary: BCrypt silently
        // ignores anything past 72 bytes, so accepting more would mean accepting a
        // password whose tail never protects anything.
        @NotBlank @Size(min = 8, max = 72) String password) {
}
