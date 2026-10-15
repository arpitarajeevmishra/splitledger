# ADR-001: Modular monolith instead of microservices

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-015

## Context

A solo developer with roughly 15 focused hours a week has to finish this. Microservices would
look impressive in a stack list, but the project's actual thesis is **money correctness**, and
splitting expenses, the ledger and settlements across services turns one database transaction into
a saga with compensating actions. That is a much harder correctness problem than the one being
demonstrated, and it would not ship.

At the same time, a single undifferentiated package proves nothing about architectural judgement
and leaves nothing extractable.

## Decision

One Spring Boot deployable, organised package-by-feature: `identity`, `group`, `expense`,
`ledger`, `settlement`, `payment`, `outbox`, `notification`, `common`. Boundaries are enforced by
**ArchUnit tests**, not convention: no cyclic dependencies, controllers never touch repositories,
`ledger` knows nothing about expenses or settlements, and only `payment` talks to Razorpay.

One extra runtime role, not one extra codebase: the notification consumer runs the **same image**
with `SPRING_PROFILES_ACTIVE=consumer`.

## Alternatives considered

- **A service per bounded context.** Pros: independent scaling and deployment, better buzzword
  surface. Cons: atomic expense-plus-ledger-plus-outbox writes become a distributed transaction;
  needs service discovery, distributed tracing and N deployment pipelines. Rejected as unfinishable
  solo, and as actively harmful to the correctness story.
- **A single flat package.** Pros: fastest to write. Cons: no demonstrable boundaries, no
  extraction path, and the `ledger` module's independence — which is what makes the audit claim
  credible — could not be enforced or shown.

## Consequences

- **Positive:** one DB transaction gives atomicity across business rows, ledger postings and the
  outbox row, which is the single most important guarantee in the system. ArchUnit makes the
  boundary claim *testable* rather than aspirational, which is a stronger interview answer than a
  diagram. `payment` and `notification` stay extractable.
- **Negative:** no independent deployability; one bad deploy takes everything down — mitigated by
  the post-deploy smoke test and automatic rollback (ADR-009).
- **Follow-up:** if this ever needed to scale out, `payment` and `notification` are the first
  extraction candidates, in that order.
