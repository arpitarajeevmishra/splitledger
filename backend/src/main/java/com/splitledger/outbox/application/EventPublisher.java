package com.splitledger.outbox.application;

import com.splitledger.outbox.domain.EventEnvelope;

/**
 * Where a published event goes.
 *
 * <p>The seam ADR-015 is built on. The deployed runtime binds the in-process implementation
 * and runs no broker; the Kafka one stays in the codebase and is exercised against an
 * embedded broker in CI, so "this design scales out" remains a claim backed by a passing
 * test rather than a diagram. Moving between them is configuration, not a redesign.
 *
 * <p><b>Must throw on failure.</b> The publisher stamps {@code published_at} only for
 * events this returned normally for, so an implementation that swallows a delivery failure
 * turns at-least-once into at-most-once, silently.
 */
public interface EventPublisher {

    void publish(EventEnvelope event);
}
