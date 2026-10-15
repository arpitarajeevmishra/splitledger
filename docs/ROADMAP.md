# Roadmap: Phase-wise Sprint Plan

> **Superseded by [`SPRINT_PLAN.md`](SPRINT_PLAN.md) (2026-10-15).** That plan replans the same
> scope across 9 sprints at the declared capacity of 13 points, splits the two overloaded
> sprints, and folds in the fixes recorded in [`REVIEW_FINDINGS.md`](REVIEW_FINDINGS.md).
> This file is kept for the story catalogue and the interview story bank in §8.

Assumes about **15 focused hours per week**. Total: Sprint 0 (3 to 4 days) + 6 one-week sprints, roughly **6 to 7 weeks**. If you have fewer hours, stretch each sprint rather than cutting the DoD.

Story points are relative (target ~13 per sprint). Recalibrate after Sprint 1.

---

## 1. Overview

| Phase | Sprint | Goal | Pts | Exit criteria |
|---|---|---|---|---|
| 0 Foundations | S0 | Repo, CI, dev data stack, skeletons | 7 | Green CI; app reaches managed Postgres; hello endpoint + page |
| 1 Identity & Groups | S1 | Auth and groups end to end | 14 | Register → login → create group → add member in UI |
| 2 Expenses, Splits & Ledger | S2 | Correct money core | 14 | Add/void/replace expenses; balances correct; property tests pass |
| 3 Settlements & Simplification | S3 | Settle up without a gateway | 15 | Cash/UPI/Sandbox settlement flow; idempotent; simplified plan |
| 4 Razorpay Test Mode | S4 | Gateway integration | 16 | Test payment confirms via webhook; duplicates and late events handled |
| 5 Events, Caching & Performance | S5 | Production-grade backend | 14 | Outbox → Kafka → notification; cache + rate limit; k6 report |
| 6 Cloud, Release & Polish | S6 | Live, documented, resume-ready | 14 | Public HTTPS URL; CD with rollback; README, demo, bullets |

### MVP cut line
**Phases 0 to 4 plus the deployment stories of Phase 6 (SL-601 to SL-603)** make a complete, shippable product. Phase 5 and the polish stories are strongly recommended but are the first things to compress if time runs short.

### Stretch backlog (post-MVP)
AI natural-language expense draft, UPI QR on desktop, recurring expenses, receipt upload (S3), Google OAuth login, Terraform + ECS Fargate, PWA, CSV export, group invite links.

---

## 2. Phase 0: Foundations (Sprint 0)

**Goal:** A working skeleton with delivery plumbing, so every later story ships through CI.

| ID | Story | Pts |
|---|---|---|
| SL-001 | Create monorepo (`backend/`, `frontend/`, `infra/`, `docs/`), README, `.gitignore`, license, branch protection on `main` | 1 |
| SL-002 | Spring Boot skeleton: Flyway `V1`, Actuator, Swagger, problem+json handler, correlation-id filter, ArchUnit baseline | 2 |
| SL-003 | React + Vite + TypeScript skeleton: ESLint, Prettier, Vitest, Tailwind, router | 1 |
| SL-004 | `docker-compose.yml` with Postgres, Redis, Redpanda, Mailhog | 1 |
| SL-005 | GitHub Actions: backend and frontend build + test, PR template, Dependabot | 1 |
| SL-006 | GitHub Project board, labels, milestones, seed backlog from this roadmap, ADR folder with ADR-001 to ADR-009 | 1 |

**Deliverables:** green CI badge, `GET /actuator/health` UP, a placeholder React page calling the API.
**Demo:** `docker compose up`, push a PR, show CI passing.

---

## 3. Phase 1: Identity & Groups (Sprint 1)

**Sprint goal:** A user can register, log in, create a group and add members from the UI.

| ID | Story | Pts | Key acceptance criteria |
|---|---|---|---|
| SL-101 | Register and login | 3 | BCrypt hashing; duplicate email → 409; validation errors in problem+json |
| SL-102 | JWT access + refresh rotation, logout | 3 | Access expires at 15 min; refresh rotates; reused refresh revokes chain |
| SL-103 | Create, list and view groups | 2 | Creator becomes ADMIN; only my groups are listed |
| SL-104 | Add/remove members; `GroupAccessGuard` | 3 | Non-member gets 404; admin-only add/remove; IDOR tests pass |
| SL-105 | Frontend: auth pages, protected routes, groups list and detail shell | 3 | Silent token refresh works; logout clears state |

**Technical tasks:** Spring Security config, JWT filter, refresh-token table, rate limiting stub, Axios interceptor, TanStack Query setup.
**Tests:** auth flow integration tests, IDOR test, token expiry test.
**Demo:** register two users in two browsers, create a group, add the second user.
**Risks:** over-engineering auth. Keep email + password only; OAuth is stretch.

---

## 4. Phase 2: Expenses, Splits & Ledger (Sprint 2)

**Sprint goal:** Members add expenses with any split type and see correct balances. This is the heart of the project.

| ID | Story | Pts | Key acceptance criteria |
|---|---|---|---|
| SL-201 | `Money` value object and four split strategies with largest-remainder rounding | 3 | ₹100 / 3 = 33.33 + 33.33 + 33.34; jqwik property: sum equals total |
| SL-202 | Create expense API with ledger postings in one transaction, group row lock, `Idempotency-Key` | 3 | Postings sum to 0; retry with same key creates one expense |
| SL-203 | List/get expenses (paginated); void and replace with reversal and `version` check | 3 | Void restores balances exactly; stale version → 409 |
| SL-204 | Balances API: net balances and pairwise debts | 2 | Matches the worked example in LLD §5.1 |
| SL-205 | Frontend: ExpenseForm + SplitEditor (live remaining counter), expense list, balances view | 3 | Cannot submit unless splits match the total; money parsing has no floats |

**Technical tasks:** Flyway migrations for expenses and ledger, `LedgerService.post(...)`, reversal logic, `money.ts`.
**Tests:** property-based split tests, ledger invariant tests, concurrent expense creation in one group, void/replace tests.
**Demo:** add a ₹1,000 expense split by shares, void it, show balances return to zero.
**Risks:** rounding edge cases and percent splits. Write the property tests first.

---

## 5. Phase 3: Settlements & Simplification (Sprint 3)

**Sprint goal:** Users can settle debts through non-gateway methods, with correctness guarantees.

| ID | Story | Pts | Key acceptance criteria |
|---|---|---|---|
| SL-301 | Settlement aggregate, state machine and `PaymentProvider` port | 2 | Illegal transitions throw `INVALID_STATE_TRANSITION` |
| SL-302 | Create settlement with `Idempotency-Key` and outstanding-debt validation | 3 | Overpay → 422; same key + same body returns same settlement |
| SL-303 | Cash and UPI-intent flows (mark paid, confirm, reject, cancel) with ledger posting on confirm | 3 | Confirm twice is a no-op; ledger posts once |
| SL-304 | Sandbox provider with configurable auto-confirm | 2 | Demo flow completes without any external service |
| SL-305 | Debt simplification endpoint | 2 | At most n-1 transfers; applying them zeroes all balances |
| SL-306 | Frontend: SettleUpDialog, method picker, UPI link, confirm/reject actions, simplified plan view | 3 | Clear "test/demo" labelling |

**Tests:** state-machine table tests, idempotency tests (same key, different body → 422), simplification property tests, 50-thread concurrent settlement test.
**Demo:** run the 3-person example from LLD §5.1 entirely through the UI.
**Risks:** pending settlements double-counting. Re-check outstanding at confirm time (documented in LLD §7.2).

---

## 6. Phase 4: Razorpay Test Mode (Sprint 4)

**Sprint goal:** A Razorpay test payment settles a debt, securely and idempotently. Heaviest sprint: if it slips, descope SL-405 polling to a manual refresh button.

| ID | Story | Pts | Key acceptance criteria |
|---|---|---|---|
| SL-401 | `RazorpayProvider`: create order (paise, receipt = settlement id), store order id, idempotent by state | 3 | Calling twice returns the same order |
| SL-402 | Verify client callback signature (`order_id|payment_id` HMAC) | 2 | Tampered signature → 400 |
| SL-403 | Webhook endpoint: raw-body signature check, dedupe by event id, confirm settlement | 3 | Valid event confirms once; invalid signature changes nothing |
| SL-404 | Failure paths: `payment.failed` logging, expiry job, late webhook on `EXPIRED` | 3 | Late captured event → `CONFIRMED`; expiry job runs every minute |
| SL-405 | Frontend: Checkout.js integration, status polling, result screens, "Test mode" banner | 3 | Payment with a test instrument ends in a green confirmation |
| SL-406 | Duplicate/concurrent webhook tests and `simulate-webhook.sh` | 2 | 20 parallel identical webhooks → 1 ledger transaction |

**Setup:** Razorpay test account, test keys and webhook secret in `.env`; enable auto-capture for test mode; expose local API through a tunnel or use the simulation script.
**Demo:** pay a debt with a test card/UPI in Checkout and watch the balance update; replay the webhook and show nothing changes.
**Risks:** webhook testing from localhost, API behaviour differences. Check the current Razorpay docs and keep the adapter thin.

---

## 7. Phase 5: Events, Caching & Performance (Sprint 5)

**Sprint goal:** Backend behaves like a production system: reliable events, fast reads, measurable performance.

| ID | Story | Pts | Key acceptance criteria |
|---|---|---|---|
| SL-501 | Transactional outbox, scheduled publisher with `SKIP LOCKED`, Kafka producer config | 3 | Business write and outbox row commit atomically; kill the broker, restart, no events lost |
| SL-502 | Notification consumer: dedupe via `processed_events`, in-app notifications, email to Mailhog, DLQ | 3 | Same event twice → one notification |
| SL-503 | Redis balance cache with after-commit eviction; Bucket4j rate limits | 3 | Redis stopped → API still works; limits return 429 |
| SL-504 | Observability: Micrometer metrics, Prometheus + Grafana profile, JSON logs with correlation id | 2 | Dashboard shows rate, latency, outbox lag |
| SL-505 | k6 load tests (cached vs uncached) and nightly ledger reconciliation job | 3 | Report committed to `docs/perf/`; reconciliation drift metric = 0 |

**Demo:** show a settlement producing an email in Mailhog; show the Grafana dashboard under k6 load.
**Deliverable for the resume:** before/after latency numbers you actually measured.

---

## 8. Phase 6: Cloud, Release & Polish (Sprint 6)

**Sprint goal:** The project is live on the internet, deploys itself, and is easy for a recruiter to understand in 3 minutes.

| ID | Story | Pts | Key acceptance criteria |
|---|---|---|---|
| SL-601 | Production Dockerfiles, `docker-compose.prod.yml`, Caddy HTTPS config | 3 | `docker compose up` works on a clean VM |
| SL-602 | AWS setup: EC2, Elastic IP, security group, domain/DNS, server `.env`, billing alarm | 2 | HTTPS site reachable; only ports 80/443 open publicly |
| SL-603 | CD: build/push to GHCR, SSH deploy, smoke test, automatic rollback | 3 | Failing smoke test restores previous version (demonstrate it once) |
| SL-604 | Documentation polish: README with diagram, GIF/video demo, Swagger link, perf report, ADRs | 3 | A stranger can run it locally in 10 minutes |
| SL-605 | Resume bullets, LinkedIn post, interview story bank | 2 | Every number traceable to a test or report |
| SL-606 | Nightly `pg_dump` to S3 and a tested restore; teardown/cost note | 1 | Restore verified on a fresh DB |

**Demo:** push a commit and watch it reach production; break the smoke test deliberately to show rollback.

### Interview story bank (write these down in `docs/interview-notes.md`)

| Topic | The story you can tell |
|---|---|
| Money correctness | Why paise, how largest-remainder works, the property tests that proved it |
| Ledger design | Why immutable postings and reversals, how you reconcile |
| Idempotency | Idempotency keys vs state-based idempotency for the gateway order |
| Webhooks | Raw-body signature verification, dedupe, late events, why the webhook is the source of truth |
| Concurrency | Per-group row lock vs optimistic version, and the 50-thread test |
| Events | Outbox pattern, at-least-once delivery, consumer dedupe |
| Performance | The k6 numbers, what you changed, what the cache did |
| Trade-offs | Modular monolith vs microservices; what you would change to scale |
| Honesty | Razorpay test mode only; what real money movement would additionally require |

---

## 9. Velocity and re-planning

| Sprint | Planned | Done | Adjustment for next sprint |
|---|---|---|---|
| S0 | 6 | | |
| S1 | 14 | | |
| S2 | 14 | | |
| S3 | 15 | | |
| S4 | 16 | | |
| S5 | 14 | | |
| S6 | 14 | | |

If Sprint 1 completes fewer than 10 points, scale all later sprints down proportionally and move Phase 5 stories into the stretch backlog first.

---

## 10. Weekly checklist

**Start of sprint**
- [ ] Sprint goal written
- [ ] Stories meet DoR and fit capacity
- [ ] Board and milestone updated

**During**
- [ ] Daily log entry
- [ ] WIP limit respected
- [ ] CI stays green on `main`

**End of sprint**
- [ ] Deployed or running in Docker locally (before Phase 6)
- [ ] Demo recorded, release tagged
- [ ] Retro written with at most 3 actions
- [ ] Velocity table updated
- [ ] Next sprint refined
