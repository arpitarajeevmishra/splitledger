package com.splitledger.outbox.application;

import com.splitledger.outbox.domain.EventEnvelope;

/**
 * Something that reacts to an event, in-process.
 *
 * <p>Implementations are found by type and called by {@code LocalEventBus}, so a module
 * subscribes by existing as a bean rather than by registering anywhere -- and the outbox
 * never learns what any of them are for.
 *
 * <p><b>Implementations must be idempotent.</b> Delivery is at-least-once by design: an
 * event whose handler succeeded but whose transaction then failed to commit is published
 * again on the next poll. That is a constraint on every handler, not an edge case
 * (ADR-005), and {@code processed_events} is how it is meant to be satisfied.
 */
public interface EventHandler {

    /** @return whether this handler is interested in that event type at all */
    boolean handles(String eventType);

    void handle(EventEnvelope event);
}
