# Interview Notes

Working notes, not a deliverable. Moved out of the README, which should describe the project to
someone evaluating the code rather than carry personal preparation material.

**The one rule here:** never write a number you have not measured. Every figure below stays a
placeholder until a test or a report produces it, and the source is named next to it.

---

## Measured results

| Metric | Value | How measured |
|---|---|---|
| Load test throughput | *not yet measured* | k6, `infra/k6/` |
| p95 latency, balances — cached vs uncached | *not yet measured* | k6 run twice, report in `docs/perf/` |
| Concurrent settlement correctness | *not yet measured* | 50-thread integration test (SL-503) |
| Duplicate webhook handling | *not yet measured* | 20-parallel-delivery test (SL-603) |
| Test coverage, domain + application | *not yet measured* | JaCoCo, added in S3 (`decision.md` D-09) |
| Live demo | *not yet deployed* | S6 |

**Already true and verifiable today:**

| Claim | Evidence |
|---|---|
| Schema is valid against real PostgreSQL 16 | Flyway applied `V1__init.sql` in CI: "Successfully applied 1 migration to schema public, now at version v1" |
| Module boundaries are enforced, not described | 9 ArchUnit rules pass in CI |
| Backend builds and tests on Java 27 / Spring Boot 4.1 | `./mvnw verify` green in CI, Testcontainers integration test included |

---

## Resume bullet drafts

Placeholders stay until measured. Each bullet should survive the question "how do you know?".

- Built SplitLedger, a full-stack expense-settlement platform (Java 27, Spring Boot 4, PostgreSQL,
  React), modelling balances as an immutable double-entry ledger in integer paise with
  largest-remainder split allocation proven by property-based tests.
- Implemented idempotent settlement APIs and Razorpay test-mode webhook processing with raw-body
  HMAC verification and event de-duplication; `<N>` parallel duplicate deliveries produced exactly
  one ledger transaction.
- Enforced module boundaries with ArchUnit and verified zero balance drift across `<N>` concurrent
  settlements using a per-group locking strategy.
- Reduced p95 balance-API latency from `<X>` ms to `<Y>` ms with cache-aside reads, measured with k6
  at `<N>` req/s.
- Shipped through GitHub Actions to a public demo with automatic rollback on failed smoke tests.

---

## Story bank

What to say when asked. The full table is in [`ROADMAP.md`](ROADMAP.md) §8; the three worth
rehearsing properly:

**"Tell me about a bug you caught early."**
The design validated settlements against *pairwise* debt, but debt simplification deliberately
produces transfers between people with no direct debt — A owes B, B owes C, so A pays C. Every
simplified transfer would have been rejected, and the UI pre-filled from exactly that plan. Caught
in review before implementation; it would otherwise have surfaced in sprint five with the frontend
already built on top of it. Fixed by validating against net balances
([ADR-010](adr/010-net-balance-settlement-validation.md)).

**"Why not microservices?"**
The project's thesis is money correctness. Splitting expenses, the ledger and settlements across
services turns one atomic database transaction into a saga with compensating actions — a harder
correctness problem than the one being demonstrated, and not finishable solo. The boundaries still
exist and are enforced by ArchUnit, so they are testable rather than aspirational, and `payment` and
`notification` remain extractable ([ADR-001](adr/001-modular-monolith.md)).

**"What would you do differently?"**
Verify every external version against its source, not just package registries. Action versions came
from a design document; `trivy-action@0.28.0` did not exist, which broke the first CI run and made
six unrelated Dependabot pull requests look broken ([`decision.md`](decision.md) D-19).
