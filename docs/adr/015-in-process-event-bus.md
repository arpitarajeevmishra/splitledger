# ADR-015: The deployed runtime drops the broker and Redis; the outbox publishes in-process

**Status:** Accepted
**Date:** 2026-10-15
**Supersedes / relates to:** ADR-005, ADR-009, ADR-013

## Context

The value of ADR-005 is the **outbox pattern**, not Kafka specifically. Running Redpanda plus
Redis alongside the API on one small host, for a demo with tens of users, costs real memory and
disk and adds two more processes that can fail to come back after a restart — while the guarantees
they are credited with (at-least-once delivery, consumer idempotency) actually live in the outbox
table and `processed_events`, not in the broker.

## Decision

Keep `outbox_events`, the publisher, the event envelope, the consumer and `processed_events`
exactly as designed, behind a **publisher interface**.

- The deployed instance binds an in-process `LocalEventBus` implementation.
- The Kafka adapter remains in the codebase and is exercised by CI integration tests against
  `EmbeddedKafkaBroker`, so the design claim stays true and testable.
- Redis is likewise optional: with a single app instance, Bucket4j rate limits and the balance
  cache run in-process (Caffeine).

## Alternatives considered

- **Run Redpanda and Redis in the deployed stack.** Pros: the deployment matches the architecture
  diagram exactly. Cons: real memory pressure and two extra failure modes, for no user-visible
  benefit at this scale.
- **Delete the broker code entirely.** Pros: less code to maintain. Cons: discards a tested,
  demonstrable design and the ability to scale out later; the outbox would lose its point.

## Consequences

- **Positive:** the deployed app has exactly one stateful dependency it does not operate itself.
  The at-least-once and idempotency guarantees are unaffected, because they were never the broker's
  job. The Kafka integration remains a truthful claim backed by passing tests.
- **Negative:** the deployment diagram differs from the architecture diagram, and **both documents
  must say so** — an undocumented divergence here would read as sloppiness rather than a decision.
- **Follow-up:** running a second app instance requires switching the bus and the cache to their
  shared implementations *first*. That is a configuration change and a deploy, not a redesign —
  which is the whole reason for the interface.
