# ADR-004: Per-group row lock for money writes; optimistic `version` for edits

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-007

## Context

Two concurrent writes in the same group can interleave and produce balances that are wrong in a
way no later read reveals. The project claims concurrency safety, so the claim needs a mechanism
that is both correct and *demonstrable* in a test. Locking everything would serialise unrelated
groups for no reason.

## Decision

Every money-affecting write opens its transaction with
`SELECT ... FROM expense_groups WHERE id = :id FOR UPDATE`. Writes within one group serialise;
unrelated groups proceed in parallel.

Layered on top:
- Expense edit and void also check `expenses.version` (optimistic lock) and return
  `409 VERSION_CONFLICT` on a stale value, so two people editing the same expense cannot both win.
- Webhook processing locks the `settlements` row before transitioning it.
- Outbox pollers use `FOR UPDATE SKIP LOCKED`, so multiple pollers are safe by construction.

## Alternatives considered

- **`SERIALIZABLE` isolation.** Pros: no explicit locks in application code. Cons: serialisation
  failures under contention mean retry logic everywhere, and the failure mode is much harder to
  test deterministically than an explicit lock.
- **Optimistic locking only.** Pros: no blocking. Cons: creating an expense writes several rows
  (expense, splits, postings); that is not a single-row compare-and-set, so lost updates remain
  possible.
- **A distributed lock in Redis.** Cons: makes Redis a *correctness* dependency, which the
  reasoning in ADR-007 explicitly rejects.

## Consequences

- **Positive:** the lock scope is exactly one group, which is also the natural shard key if this
  ever grew. The guarantee is provable by three tests that are worth demonstrating: 50 threads
  settling slices of one debt, 20 parallel identical webhooks producing one ledger transaction, and
  two simultaneous edits where exactly one succeeds.
- **Negative:** a very active group serialises its own writes.
- **Follow-up:** taking the lock must live in one place in the application layer. Scattered across
  services, it will eventually be forgotten in exactly the path that matters.
