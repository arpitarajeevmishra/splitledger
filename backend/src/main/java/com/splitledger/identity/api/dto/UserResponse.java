package com.splitledger.identity.api.dto;

import com.splitledger.identity.domain.User;
import java.util.UUID;

public record UserResponse(UUID id, String email, String name, String upiId) {

    public static UserResponse from(User user) {
        return new UserResponse(user.id(), user.email(), user.name(), user.upiId());
    }
}
