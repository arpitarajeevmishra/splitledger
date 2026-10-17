package com.splitledger.identity.api.dto;

import com.splitledger.identity.domain.User;
import java.util.UUID;

/**
 * A user's view of **their own** profile. Named for that on purpose: it carries
 * {@code upiId}, and a payment address is only ever shown to its owner and to the
 * counterparty of an actual settlement (LLD section 12).
 *
 * <p>When group member lists arrive in S2 they need a different type that has no
 * {@code upiId} component at all, rather than this one with a field left out by
 * convention. Making the leak impossible to write beats remembering not to write it.
 */
public record SelfProfileResponse(UUID id, String email, String name, String upiId) {

    public static SelfProfileResponse from(User user) {
        return new SelfProfileResponse(user.id(), user.email(), user.name(), user.upiId());
    }
}
