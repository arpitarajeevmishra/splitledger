package com.splitledger.group.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param currency optional; absent means INR, and INR is the only accepted value in v1 */
public record CreateGroupRequest(
        @NotBlank @Size(min = 1, max = 100) String name,
        @Size(min = 3, max = 3) String currency) {
}
