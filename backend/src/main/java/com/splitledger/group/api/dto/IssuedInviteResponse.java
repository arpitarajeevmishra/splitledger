package com.splitledger.group.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The response to creating an invitation, and the only place the invite link appears.
 *
 * <p>Until SL-902 wires real email, the admin copies this URL and passes it on themselves.
 * Returning it beats logging it: a log line is a working credential sitting in a file
 * nobody rotates, readable by anyone with log access and shipped off to whatever
 * aggregator collects it, whereas this response goes only to the admin who just asked for
 * it over TLS.
 *
 * @param url the one and only time the raw token is readable; it is stored hashed
 */
public record IssuedInviteResponse(
        UUID id,
        String email,
        String url,
        Instant expiresAt) {
}
