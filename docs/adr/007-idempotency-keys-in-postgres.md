# ADR-007: Idempotency keys in PostgreSQL, not Redis

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-004

## Context

A retried `POST` must not create a second expense or a second settlement. The store that records
"this key was used, and here is the response it produced" is therefore part of the money-correctness
guarantee, not a performance optimisation. Separately, the caching design states that a Redis
outage must degrade to the database and never fail a request — so Redis cannot hold anything whose
loss causes incorrect behaviour.

## Decision

An `idempotency_keys` table with primary key `(user_id, idem_key)`, storing a hash of the request
body, a status, and the stored response status and body. Required on `POST /expenses` and
`POST /settlements`.

The `IN_PROGRESS` marker is written in a **`REQUIRES_NEW`** transaction and committed before the
business logic runs, so a concurrent duplicate can actually observe it and receive
`409 IDEMPOTENCY_IN_PROGRESS`. Same key with a different body returns `422`. Rows are purged after
24 hours; markers stuck `IN_PROGRESS` by a crash are reclaimed after 10 minutes.

The gateway-order endpoint is idempotent **by state** instead: if `gateway_order_id` is already
stored, return it rather than creating another order.

## Alternatives considered

- **Redis with a TTL.** Pros: fast, natural expiry, no purge job. Cons: Redis is explicitly
  allowed to fail and be lost in this design; keeping the idempotency record there means a Redis
  restart can permit a double-charge. Making it durable enough would mean operating Redis as a
  system of record, which contradicts its stated role.
- **No idempotency; rely on the client not retrying.** Not acceptable on a money endpoint, where
  retries are exactly what networks do.

## Consequences

- **Positive:** the guarantee survives a Redis outage, an app restart, or losing the cache
  entirely. Storing the response means a retry returns the *original* result rather than a
  conflict, which is what callers actually want. Redis stays a pure cache, so its failure mode
  remains trivial.
- **Negative:** one extra write on the hot path, and a purge job to own.
- **Follow-up:** the `REQUIRES_NEW` detail is load-bearing. Without it the advertised 409 branch is
  unreachable, which was a real defect in the first draft of the design.
