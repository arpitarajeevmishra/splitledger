# Architecture Decision Records

One file per decision, in the format from [`../AGILE.md`](../AGILE.md) §10.7: context, decision,
alternatives considered, consequences. A record is written when a choice is made that someone —
including you in three months — might otherwise have to reverse-engineer from the code.

ADR-001 to ADR-009 were taken while writing the HLD and LLD. ADR-010 to ADR-015 came out of the
design review recorded in [`../REVIEW_FINDINGS.md`](../REVIEW_FINDINGS.md); ADR-010 in particular
fixed a contradiction that would otherwise have broken the project's headline feature.

| ADR | Decision | Status |
|---|---|---|
| [001](001-modular-monolith.md) | Modular monolith instead of microservices | Accepted |
| [002](002-money-as-integer-paise.md) | Money as integer paise (`BIGINT` / `long`) | Accepted |
| [003](003-immutable-ledger.md) | Immutable ledger: corrections by reversal, edits by replace | Accepted |
| [004](004-per-group-locking.md) | Per-group row lock for money writes; optimistic `version` for edits | Accepted |
| [005](005-transactional-outbox.md) | Transactional outbox for event publishing | Accepted |
| [006](006-payment-provider-port.md) | A `PaymentProvider` port with Razorpay as one adapter | Accepted |
| [007](007-idempotency-keys-in-postgres.md) | Idempotency keys in PostgreSQL, not Redis | Accepted |
| [008](008-trunk-based-development.md) | Trunk-based development with short-lived branches | Accepted |
| [009](009-single-host-docker-compose.md) | A single host with Docker Compose for deployment | Accepted |
| [010](010-net-balance-settlement-validation.md) | Settlements validated against **net** balances, not pairwise debt | Accepted |
| [011](011-token-storage.md) | Access token in memory; refresh token in an `httpOnly` cookie | Accepted |
| [012](012-no-local-database-or-docker.md) | No database or Docker on the development machine | Accepted |
| [013](013-public-demo-posture.md) | Ship as a public demo: Razorpay test mode live, UPI intent disabled | Accepted |
| [014](014-account-deletion-anonymises.md) | Account deletion anonymises the user and retains postings | Accepted |
| [015](015-in-process-event-bus.md) | Deployed runtime drops the broker and Redis; outbox publishes in-process | Accepted |

## The three worth reading first

If you are reviewing this project and only read three: **010** (the contradiction that review
caught), **013** (why no real money moves anywhere, and why UPI intent is the *risky* method rather
than the safe one), and **007** (why the idempotency record cannot live in Redis).
