package com.splitledger.group.api.dto;

import com.splitledger.group.domain.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * A member as seen by the rest of the group: name and role, nothing else.
 *
 * <p>Deliberately has no email and no {@code upiId} component. Adding someone to a group
 * must not broadcast their address book entry, and a payment address goes only to its
 * owner and to the counterparty of an actual settlement -- which is why the self view
 * lives in a separate type called {@code SelfProfileResponse} (D-36). A field that does
 * not exist cannot leak.
 */
public record GroupMemberResponse(UUID userId, String name, Role role, Instant joinedAt) {
}
