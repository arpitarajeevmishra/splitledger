# ADR-012: No database or Docker on the development machine

**Status:** Accepted
**Date:** 2026-10-15
**Supersedes / relates to:** ADR-009

## Context

The development machine is not to run a local database or a Docker daemon, and currently has
neither a JDK nor Maven installed. Meanwhile the design depends on specific PostgreSQL behaviour —
`SELECT ... FOR UPDATE`, `SKIP LOCKED`, `JSONB`, partial indexes, `TIMESTAMPTZ` — and Testcontainers
requires Docker. Substituting an embedded SQL engine would quietly invalidate the concurrency and
outbox designs while still showing green tests, which is worse than having no tests.

## Decision

Development uses **managed free-tier** PostgreSQL, Redis and SMTP. Nothing is installed locally
beyond a JDK and Node; Maven is the wrapper (`./mvnw`).

Test placement:
- **Locally:** unit and jqwik property tests, fully offline; plus a `@Tag("cloud")` suite against
  the managed dev database.
- **In CI only:** the Testcontainers integration suite and the Playwright e2e suite, where
  GitHub-hosted runners provide Docker.
- **In-JVM where it suffices:** `EmbeddedKafkaBroker` for the event path, GreenMail for email.

Docker therefore appears in exactly two places: CI runners and the deployment host.

## Alternatives considered

- **H2 or SQLite locally.** Pros: offline, instant, zero setup. Cons: the row-locking and
  `SKIP LOCKED` semantics that every correctness claim in this project rests on do not exist there,
  so a green local run would mean nothing. This is the alternative that looks most attractive and
  is most dangerous.
- **Zonky embedded-postgres.** Pros: a real PostgreSQL, offline. Cons: it downloads and runs a
  PostgreSQL binary as a local process — the thing being avoided.
- **Install PostgreSQL natively.** Same objection, plus it persists between sessions.

## Consequences

- **Positive:** the development database behaves exactly like production, so the locking and
  outbox tests are meaningful. Almost nothing is installed on the machine.
- **Negative:** local integration tests need connectivity and are slower. Free tiers sleep,
  throttle and expire, so cold starts must be tolerated and nothing demo-critical may live only
  there.
- **Follow-up — this changes the Definition of Done:** a story is Done when **CI** is green. A
  passing local run is necessary but no longer sufficient.
