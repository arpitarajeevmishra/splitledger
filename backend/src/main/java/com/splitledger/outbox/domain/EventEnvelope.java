package com.splitledger.outbox.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One event, as it goes to a bus.
 *
 * <p>Deliberately not the entity. What is published is a value with no identity, no
 * lifecycle and no Hibernate session behind it -- a consumer that received the entity could
 * stamp {@code publishedAt} on it, and an in-process bus would make that stick.
 *
 * @param payload the event body as JSON text, opaque here on purpose: the outbox moves
 *     events and does not get a vote on what any module puts in one
 */
public record EventEnvelope(UUID eventId, String aggregateType, UUID aggregateId, UUID groupId,
        String eventType, String payload, Instant occurredAt) {

    /**
     * The partition key, which is what per-group ordering is made of (ADR-005).
     *
     * <p>Group first, because two expenses in one group must arrive in the order they were
     * written and two unrelated groups need not. An event with no group falls back to its
     * own aggregate, which is the right key for something ordered per-aggregate -- and is
     * never null, so there is no such thing as an unkeyed event.
     */
    public String key() {
        return groupId != null ? groupId.toString() : aggregateId.toString();
    }
}
