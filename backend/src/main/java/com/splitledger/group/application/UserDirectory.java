package com.splitledger.group.application;

import java.util.Optional;
import java.util.UUID;

/**
 * The only thing the group module needs to know about people: how to map between a user
 * id and an email address.
 *
 * <p>A port rather than a direct call into the identity module. Invites are addressed to
 * an email, membership is recorded against a user id, and something has to bridge the
 * two; confining that to one named interface keeps the reach across the module boundary
 * visible in the type system instead of buried in a service, and lets the invite rules be
 * tested without a database or a user table.
 */
public interface UserDirectory {

    /** Empty when nobody has registered that address -- an invite to a stranger is fine. */
    Optional<UUID> userIdFor(String email);

    Optional<String> emailOf(UUID userId);

    /** Must match the identity module's rule, or an invite would never find its user. */
    String normaliseEmail(String email);
}
