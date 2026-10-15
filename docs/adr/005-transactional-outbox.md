# ADR-005: Transactional outbox for event publishing

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-015

## Context

An expense write must reliably produce an `expense.created` event, but the database and the
broker are two systems with no shared transaction. Publishing before commit can announce a fact
that then rolls back. Publishing after commit can lose the event entirely if the process dies in
between. Neither is acceptable for a system whose selling point is that it does not lose or
duplicate things.

## Decision

Write the business rows **and** an `outbox_events` row in the same database transaction. A
scheduled publisher then selects unpublished rows `FOR UPDATE SKIP LOCKED`, publishes each with
`groupId` as the key (giving per-group ordering), and stamps `published_at`.

Delivery is explicitly **at-least-once**; consumers de-duplicate by inserting into
`processed_events (consumer, event_id)` and skipping on conflict. The publisher is
`@Profile("!consumer")` and the listener `@Profile("consumer")`, so the two runtime roles sharing
one image do not both poll.

## Alternatives considered

- **Publish inside the transaction.** Pros: trivial. Cons: a later rollback leaves a published
  event describing something that never happened — the worst failure mode of the three.
- **Publish immediately after commit.** Pros: simple, no extra table. Cons: a crash in the
  microsecond window between commit and publish loses the event silently and permanently.
- **Change-data-capture (Debezium).** Pros: no application code, no poller, no dual write. Cons:
  another distributed system to operate, which is far more weight than this deployment justifies.

## Consequences

- **Positive:** no lost events and no phantom events. Broker downtime is survivable: rows simply
  accumulate and publishing resumes. `SKIP LOCKED` means horizontal scaling of the publisher needs
  no new design.
- **Negative:** up to one poll interval of latency. Requires an `outbox_lag_seconds` metric to be
  observable at all. Consumers **must** be idempotent — a design constraint, not an afterthought.
- **Follow-up:** ADR-015 keeps this pattern while removing the broker from the deployed runtime.
