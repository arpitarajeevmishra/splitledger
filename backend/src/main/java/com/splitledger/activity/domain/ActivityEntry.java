package com.splitledger.activity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing that happened in a group.
 *
 * @param actorId who did it, as far as the records can say. Null only for an expense
 *     voided before V3 added the columns to record it -- the feed says "someone" rather
 *     than guessing, because naming the wrong person is worse than naming nobody.
 * @param amountPaise null for events that move no money
 * @param counterpartyId the other side of a settlement; null otherwise
 */
public record ActivityEntry(
        ActivityKind kind,
        UUID subjectId,
        UUID actorId,
        Instant at,
        String description,
        Long amountPaise,
        UUID counterpartyId,
        boolean movedMoney) {
}
