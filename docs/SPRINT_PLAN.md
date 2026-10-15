# SplitLedger — Sprint Plan (revised)

**Supersedes:** `ROADMAP.md` §1–§8 · **Companion to:** `REVIEW_FINDINGS.md`
**Date:** 2026-10-15 · **Status:** proposed, not started

---

## 1. Planning basis

| Input | Value |
|---|---|
| Capacity | ~15 focused hours/week → **13 story points/sprint** (`AGILE §1`) |
| Sprint length | 1 week, 10 sprints → **~10 weeks** |
| Total | 132 points (**6 already delivered** — see §3) |
| Estimation | Fibonacci 1/2/3; anything larger is split (unchanged) |
| Data stores | **Managed cloud free tiers** — no local database, no local Docker (`REVIEW_FINDINGS §5`) |
| Docker | Built in CI, run on the deploy VM only |

### What changed from `ROADMAP.md`, and why

1. **13 points per sprint, not 14–16.** The old plan was over its own declared capacity in every sprint, with Sprint 4 at 16. Over-committing on sprint 1 of 7 is how solo projects stall.
2. **Sprint 0 does real work.** It now resolves the three open design decisions (B1, B5, B7) and fixes the docs. Starting Sprint 1 on top of a contradiction (B1) would be discovered in Sprint 5, at the worst possible time.
3. **Old Sprint 1 split in two** (Identity | Groups+invites). The invite flow (`M1`) was missing entirely and is the most-used action in the product.
4. **Old Sprint 2 split in two** (money core | balances+simplification+activity). This was 5 stories carrying the entire correctness thesis of the project; it was the likeliest sprint to overrun.
5. **The activity feed is built where its data is born** (Sprint 4), not assumed into existence at the end.
6. **10 sprints, not 7.** Nine to build it, one to make it safe to show strangers. Same scope,
   honest arithmetic; compressing to 7 was only possible by ignoring capacity.
7. **Deployment moved from S8 to S6, five sprints earlier.** Two reasons. The gateway sprint's
   hardest problem is webhook delivery, and a stable public HTTPS URL replaces a tunnel that has to
   be restarted and re-registered every session — so deploying first makes the heaviest sprint
   easier. And with disposable data and no real money (ADR-013) there is no reason to wait. The
   app goes up with `REGISTRATION_ENABLED=false`, so being live does not mean being exposed.

### Two cut lines, because there are two audiences

**Portfolio cut line — S0 to S7.** A complete, deployed, demonstrable product: auth, groups,
invites, expenses, ledger, balances, simplification, settlements, Razorpay test mode, a public
HTTPS URL and CD with automatic rollback — with registration closed, so it is yours to demo.
**S8** (events, cache, performance) is the first thing to compress; it is the resume
differentiator, not the product.

**Public-demo cut line — the above plus S9.** Strangers signing up need email verification,
deliverable mail, honest demo framing, a guest tour, a privacy policy, deletion and abuse caps.
See [`RUNNING_LIVE.md`](RUNNING_LIVE.md) for the operating posture, hosting options and real cost.

**No real money moves anywhere in this product.** Settlement in the deployed instance is Razorpay
**test mode**, cash, or the sandbox provider. `UPI_INTENT` is built and tested but disabled in the
deployed instance, because it is the only method that could reach a real bank account and the
expense data here is fiction (`RUNNING_LIVE.md §2`). The demo **is** production — there is one
deployed environment, not two.

---

## 2. Overview

| Sprint | Goal | Pts | Exit criteria |
|---|---|---|---|
| **S0** Foundations & decisions | Decisions closed, docs corrected, cloud dev stack, green CI | 15 (**6 done**) | `./mvnw spring-boot:run` reaches managed Postgres; CI green; all 15 ADRs written |
| **S1** Identity | Register, login, refresh rotation, profile | 13 | Two users log in from two browsers; reused refresh token revokes the chain |
| **S2** Groups, membership & invites | Groups with real membership and consent | 13 | Invite an unregistered email → they register → they join; non-member gets 404 |
| **S3** Money core | Splits and ledger that cannot drift | 13 | ₹100/3 = 33.33/33.33/33.34; every transaction sums to 0; property tests pass |
| **S4** Balances, corrections & activity | Correct balances, reversible history | 13 | Add → void → balances return to exactly zero; activity feed explains every change |
| **S5** Settlements (no gateway) | Settle up, idempotently | 13 | Cash/UPI/Sandbox settle a simplified-plan transfer; confirm twice posts once |
| **S6** Deploy it, door shut | Live on the internet, registration off | 13 | A merge reaches a public HTTPS URL; a broken smoke test rolls back; strangers cannot register |
| **S7** Razorpay test mode | Gateway integration, securely | 13 | Test payment confirms via a real webhook URL; 20 parallel duplicates → 1 ledger transaction |
| **S8** Events, cache, performance | Production-grade backend | 13 | Outbox → consumer → notification; cache down → API still works; measured k6 report |
| **S9** Open it to the public | Safe and legible to strangers | 13 | Registration on behind verification; guest tour lands on seeded data; invites reach inboxes |

Bracketed tags like **[B1]** reference `REVIEW_FINDINGS.md`.

---

## 3. Sprint 0 — Foundations & decisions (15; 6 delivered, 9 remaining)

**Goal:** every open design question is closed, the docs match reality, and a trivial change can
travel through CI to a green build.

> **Sprint 0 is the one sprint over capacity, and deliberately so** — its stories sum to 15, not
> 13, and the two largest are already finished. SL-002 and SL-003 were completed on 2026-10-15:
> all fifteen ADRs are written in [`adr/`](adr/README.md), and the C1–C22 correction pass has been
> applied across the HLD, LLD, README, CI/CD doc and roadmap (see
> [`REVIEW_FINDINGS.md`](REVIEW_FINDINGS.md) §0). **9 points remain**, which fits a week
> comfortably.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-001 ✅ | `git init`, monorepo skeleton (`backend/ frontend/ infra/ docs/ scripts/ .github/`), `.gitignore` covering `.env`, licence, branch protection on `main`, and the **personal git identity and SSH remote set up before the first commit** ([`ACCOUNTS_AND_ACCESS.md`](ACCOUNTS_AND_ACCESS.md)) | 1 | `git log` shows the personal name and email on commit #1; `git remote -v` uses the `github-personal` SSH alias; `main` requires a PR + green CI; `.env` is ignored **[B2]** |
| SL-002 ✅ | Close the three open decisions as ADRs: **ADR-010** settlements validate against *net* balances (not pairwise), **ADR-011** refresh token in `httpOnly;Secure;SameSite=Strict` cookie + access token in memory, **ADR-012** cloud dev data stores / Docker in CI only. Also write up ADR-001…009 as files, and a short privacy note answering "delete my account" against an immutable ledger | 3 | Each ADR states context, decision, alternatives, consequences **[B1][B5][B7][M3]** |
| SL-003 ✅ | Documentation correction pass: apply C1–C22 to `HLD.md`/`LLD.md`, fix the Sprint-0 points total, settle the webhook path once, unify the optimistic-locking convention, add the missing DDL constraints and indexes to `V1__init.sql` | 3 | A diff per finding; no open contradiction remains **[B6][C1–C22]** |
| SL-004 ◑ | Cloud dev data stack: managed Postgres (Neon), Redis (Upstash), SMTP (Mailtrap); real `.env.example` with one canonical name per secret; connectivity check script | 2 | `.env.example` and `scripts/check-env.sh` written and tested against both reachable and broken config. **Accounts still to be provisioned by you** — that half cannot be automated **[B7][C12]** |
| SL-005 | Backend skeleton: JDK 21 + Maven Wrapper, Flyway `V1`, Actuator, springdoc (disabled in `prod`), problem+json handler, correlation-id filter, raw-body filter for `/webhooks/*`, ArchUnit baseline | 2 | `/actuator/health` UP against managed Postgres; ArchUnit runs **[C19]** |
| SL-006 ✅ | Frontend skeleton: Vite + TS (strict) + Tailwind v4 + router + oxlint/Prettier/Vitest, `.nvmrc`, relative `/api/v1` base URL with a dev proxy | 1 | Placeholder page calls the API and renders the response; typecheck, lint, test and build all pass **[C13][C16]** |
| SL-007 ✅ | CI pipeline: backend (`verify`, Testcontainers **CI-only**, JaCoCo gate), frontend (lint/typecheck/test/build), Trivy (secrets blocking, vulns non-blocking + `.trivyignore` ritual), PR template, Dependabot | 2 | Green on a trivial PR; a planted fake secret fails the build. Backend/e2e/publish jobs are gated on a `detect` job until SL-005 and SL-801 land **[C15]** |
| SL-008 | GitHub Project board, labels, milestones S0–S9, backlog seeded from this plan, `docs/sprints/velocity.md` | 1 | Every story below exists as an issue in a milestone |

**Demo:** open a PR, watch CI go green, show `/actuator/health` talking to a cloud database with no Docker running.
**Risk:** SL-002/003 feel like "not real work". They are the cheapest they will ever be.

---

## 4. Sprint 1 — Identity (13)

**Goal:** a user can register, log in, stay logged in, and manage their profile.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-101 | Register + login | 3 | BCrypt ≥10; duplicate email → 409; validation errors as problem+json; password ≥8 |
| SL-102 | JWT access (15 min) + refresh rotation per ADR-011, logout | 3 | Refresh rotates on every use; reusing an old refresh token revokes the whole chain; logout clears the cookie |
| SL-103 | Bucket4j rate limits on auth (login 5/min per IP+email, register 5/h per IP) against managed Redis | 2 | 6th login attempt → 429 with `Retry-After`; Redis unavailable → requests still served |
| SL-104 | Frontend auth: login/register pages, protected routes, silent-refresh Axios interceptor, auth state | 3 | Access token never in `localStorage`; a 401 triggers one refresh then one retry; logout clears state |
| SL-105 | `GET/PATCH /users/me` (name, UPI id) with the VPA visibility rule | 2 | A user's VPA is never returned to anyone but themselves and a settlement counterparty |

**Tests:** auth integration suite, token-expiry, refresh-reuse revocation, rate-limit.
**Risk:** over-engineering auth. Email + password only; OAuth stays in the backlog.

---

## 5. Sprint 2 — Groups, membership & invites (13)

**Goal:** real groups with real membership rules — and nobody is added without consent.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-201 | Create / list / view groups; creator becomes ADMIN; `expense_groups.currency` (INR-only in v1) | 2 | Only my groups are listed; non-INR currency → clear error **[C21]** |
| SL-202 | `GroupAccessGuard` + the 404-not-403 rule, applied at every service entry point | 3 | A member of group A gets **404** for every group-B route; an IDOR test exists per endpoint **[C10]** |
| SL-203 | Add / remove member (admin only), re-join via upsert clearing `left_at`, removal blocked on non-zero balance | 3 | Re-adding a departed member works; removing a member who owes money → 422 **[C22]** |
| SL-204 | Invite flow: invite by email (registered or not), token link, accept/decline, expiry, rate-limited | 3 | An unregistered email can register through the invite and land in the group; nobody appears in a group without accepting **[M1][M7]** |
| SL-205 | Frontend: groups list + create, group shell with tabs, members panel, invite dialog | 2 | Minimal member profile shown (name only); emails not broadcast to the group |

**Demo:** invite a second email address, accept it in another browser, show the member list.

---

## 6. Sprint 3 — Money core: splits, expenses, ledger (13)

**Goal:** the part the whole project is about. Money cannot drift.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-301 | `Money` value object + EQUAL/EXACT/PERCENT/SHARES via largest remainder, with caps on amount (≤ ₹10,00,000), shares (≤1,000) and participants (≤50) | 3 | jqwik: sum == total, nothing negative, error <1 paisa, deterministic — at the caps too **[C2][C3][M6]** |
| SL-302 | `ledger` module: `post()`, zero-sum invariant, reversal support, `UNIQUE(type, source_id)` for non-reversals | 2 | A second post for the same source is rejected by the database **[C4]** |
| SL-303 | `POST /groups/{id}/expenses`: group row lock, expense + splits + postings in one transaction, payer and every participant validated as active members | 3 | Postings sum to 0; a participant from another group → 422; concurrent creations in one group serialise **[C10]** |
| SL-304 | Idempotency: `Idempotency-Key` on expense creation, per the C1 decision (separate-transaction marker **or** block-and-replay — one, documented) | 3 | Same key + same body → one expense, same response; same key + different body → 422 **[C1]** |
| SL-305 | `money.ts`: string → paise, paise → ₹ | 2 | Vitest covers `"0.1"`, `"1,000.50"`, `".5"`, `"1.005"`, `"-1"`, `"1e3"`, empty; no float arithmetic anywhere |

**Demo:** add a ₹1,000 shares-split expense, print the postings, show they sum to zero.
**Risk:** this is the sprint to protect. Write the property tests first.

---

## 7. Sprint 4 — Balances, corrections & activity (13)

**Goal:** balances anyone can trust, history anyone can audit.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-401 | Balances API: net (from postings) + pairwise (from source tables) | 2 | Matches the worked example in `LLD §5.1`; both derivations agree |
| SL-402 | Void and replace with reversal, `If-Match: version` on both, `replaces_expense_id` chain | 3 | Void restores balances **exactly**; stale version → 409; nothing is ever mutated **[C8]** |
| SL-403 | Simplification endpoint (greedy min cash flow) | 2 | ≤ n-1 transfers; applying them zeroes every net balance; property-tested |
| SL-404 | Activity feed: derived view over expenses + settlements + membership, paginated | 2 | Every balance change in the group has a feed entry naming who did what **[B3]** |
| SL-405 | Frontend: ExpenseForm + SplitEditor (live "remaining" counter), expense list, balances view | 3 | Cannot submit unless splits equal the total to the paisa; shows who absorbs the leftover paisa |
| SL-406 | Concurrency tests: parallel expense creation in one group; two simultaneous edits of one expense | 1 | Exactly one edit wins; no drift |

**Demo:** the 3-person worked example end-to-end in the UI, then void it and show zeroes.

---

## 8. Sprint 5 — Settlements without a gateway (13)

**Goal:** settle up correctly, with no external dependency in the loop.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-501 | `Settlement` aggregate + state machine, including `INITIATED → EXPIRED`, and defined `expires_at` rules per method | 3 | Illegal transitions throw `INVALID_STATE_TRANSITION`; table-driven test over every pair **[C17]** |
| SL-502 | Create settlement: **net-balance** validation per ADR-010, `from` = caller, `from != to`, both active members, `Idempotency-Key` | 3 | A transfer straight from the simplified plan is **accepted**; genuine overpayment → 422 **[B1]** |
| SL-503 | Cash + UPI intent: mark-paid / confirm / reject / cancel; ledger posted on confirm; outstanding re-checked at confirm; overpayment surfaced as a reverse debt | 3 | Confirm twice → one ledger transaction, success both times; void a settled expense → coherent "overpaid" state **[C9]** |
| SL-504 | Sandbox provider (configurable auto-confirm) + `GET /settlements/{id}` | 2 | Demo settles with no external service; the UI can poll a single settlement **[B4]** |
| SL-505 | Frontend: SettleUpDialog, method picker, UPI intent link, confirm/reject actions, simplified-plan view | 2 | "Demo payment" / "no real money moves" labelling on every non-cash method |

**Tests:** state-machine matrix, idempotency (same key/different body → 422), 50-thread concurrent settlement of one debt.

---

## 9. Sprint 6 — Deploy it, with the door shut (13)

**Goal:** the app is live on the internet, deploying itself on every merge — and **registration is
switched off**, so only you can sign in. Hardening it for strangers is S9.

**Why deployment moved forward five sprints.** With no real-money risk and disposable data
(ADR-013) there is no reason to keep the app on a laptop until week nine. More concretely: Sprint 7
is the heaviest sprint in the project and most of its difficulty is webhook delivery. A stable
public HTTPS URL means you register `https://<domain>/webhooks/razorpay` in the Razorpay test
dashboard **once** and work normally, instead of restarting a tunnel every session and re-pointing
the dashboard at a new URL each time. Deploying first makes the hard sprint materially easier.

**The kill switch is what makes this safe.** `REGISTRATION_ENABLED=false` from the first deploy.
The app is reachable, you are the only account, and the signup hole that email verification closes
(SL-901) simply is not exposed yet.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-801 | Deployable Dockerfiles (+ `frontend/.dockerignore`, relative `/api/v1`), `docker-compose.prod.yml` with the owner templated, Caddy HTTPS, springdoc off in `prod`, security headers/CSP | 3 | Clean host comes up from compose; no `<owner>` placeholder; Swagger not public **[C13][C19][M9][M10]** |
| SL-802 | Host + managed Postgres: instance, DNS, firewall (22 from your IP, 80, 443), server `.env` at `chmod 600`, **billing alarm first**, managed Postgres with the app host holding no state | 2 | HTTPS reachable; nothing else open; destroying and recreating the host loses no data (ADR-015) |
| SL-803 | CD: GHCR push on `main`, deploy, smoke test (`/actuator/health` + `/api/v1/health-check`), automatic rollback — demonstrated once | 3 | A deliberately broken smoke test restores the previous SHA **[C11][C14]** |
| SL-804 | Nightly `pg_dump` off-provider + a **read-back check**; teardown/cost note | 1 | The dump is restorable and you have restored it once |
| SL-903 | Demo framing (`RUNNING_LIVE.md §3`, D1–D5) and `PAYMENTS_UPI_INTENT_ENABLED=false` | 2 | Non-dismissible "Razorpay test mode — no real money moves" banner on every page; "don't enter real financial information" at signup; the result screen reads "simulated payment — no funds were transferred"; the UPI method is absent from the deployed UI (ADR-013) |
| SL-906 | `REGISTRATION_ENABLED` kill switch, per-account caps (groups, members, expenses/day), rate limits on invite and email paths | 1 | Registration is off by default and toggles without a deploy; caps return a clear 422 |
| SL-907 | Uptime monitor on `/api/v1/health-check` (which doubles as the keep-warm ping), Sentry, and the `RUNNING_LIVE.md §8` alert policy with quiet hours on everything | 1 | A deliberately stopped app alerts within 15 minutes; a forced ledger drift alerts immediately |

**Demo:** push a commit and watch it reach production; break the smoke test on purpose and watch
the rollback; show that a stranger hitting the URL cannot register.

---

## 10. Sprint 7 — Razorpay test mode (13)

**Goal:** a test payment settles a debt, idempotently and verifiably — against a real, stable
webhook URL.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-601 | `RazorpayProvider`: create order (paise, `receipt = settlementId`), idempotent by stored `gateway_order_id` under the row lock, ₹1 minimum enforced before the call | 3 | Calling twice returns the same order; a ₹0.40 Razorpay settlement is rejected *by us*, with our error code **[C5]** |
| SL-602 | Client callback verification (`order_id\|payment_id` HMAC, constant-time) | 2 | Tampered signature → 400, nothing changes |
| SL-603 | Webhook endpoint at `/webhooks/razorpay`: raw-body HMAC, `(provider, event_id)` de-duplication, settlement resolved **only** via stored order id, confirm in one transaction; 20-parallel-duplicate test; `scripts/simulate-webhook.sh` | 3 | 20 identical concurrent deliveries → exactly 1 ledger transaction; invalid signature → 400 and no state change **[B6]** |
| SL-604 | Failure paths: `payment.failed` recorded as an attempt, expiry job (every minute), late `payment.captured` on an `EXPIRED` settlement → `CONFIRMED` | 2 | Late-webhook test passes; expiry sweeps both INITIATED and PENDING |
| SL-605 | Frontend: Checkout.js, status polling on `GET /settlements/{id}`, result screens, **Razorpay's published test instrument details rendered next to the Pay button** | 3 | A test-instrument payment ends in a green confirmation and an updated balance. The test-card hint is not optional: without it a visitor reaches the gateway with nothing to type (`RUNNING_LIVE.md §3`, D4) |

**Setup:** Razorpay test account, auto-capture enabled, and the webhook registered against the
S6 deployment. `scripts/simulate-webhook.sh` remains the offline path for tests.
**Risk:** still the heaviest sprint, but a real webhook URL removes its worst friction. If it
slips, descope SL-605 polling to a manual refresh button.

---

## 11. Sprint 8 — Events, cache, observability, performance (13)

**Goal:** the backend behaves like a production system, and the numbers are measured rather than
claimed.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-701 | Transactional outbox + scheduled publisher with `SKIP LOCKED`, `@Profile("!consumer")`; publisher behind an interface with two implementations — in-process `LocalEventBus` (deployed) and Kafka (CI tests against `EmbeddedKafkaBroker`) | 3 | Business write + outbox row commit atomically; killing the bus and restarting loses no event and publishes none twice (ADR-015) **[C6]** |
| SL-702 | Notification consumer: `processed_events` de-duplication, in-app notifications, email, DLQ after 3 retries; `POST /notifications/{id}/read` | 3 | Same event twice → one notification; the bell can be cleared **[M2]** |
| SL-703 | Balance cache evicted `afterCommit`; in-process (Caffeine) with the Redis implementation behind the same interface | 2 | Cache unavailable → API serves from the DB with no error; a stale balance is never served after a commit |
| SL-704 | Observability: Micrometer metrics (incl. `webhook_duplicates_total`, `outbox_lag_seconds`, `ledger_reconciliation_drift`), dashboard, JSON logs with `correlationId`, alert when drift ≠ 0 | 2 | Dashboard shows rate/latency/outbox lag; a forced drift fires the alert **[M8]** |
| SL-705 | Seed/demo-data script, k6 run cached vs uncached, nightly reconciliation job | 3 | `docs/perf/` holds the report; drift metric 0; the seed script is reused by both k6 and the S9 guest tour **[M11]** |

**Deliverable:** the only latency numbers that may ever appear on a resume.
**Compressible:** if time runs out, this is the sprint to cut. It is the resume differentiator, not
the product — and SL-705's seed script is needed by S9, so keep that story if you drop the rest.

---

## 12. Sprint 9 — Open it to the public (13)

**Goal:** flip registration on, safely. A visitor understands what the app is and sees it doing
something interesting within five seconds.

| ID | Story | Pts | Acceptance |
|---|---|---|---|
| SL-901 | **Email verification**, and an unverified account cannot accept an invite. `REGISTRATION_ENABLED=true` ships *with* this story, not before it | 3 | Closes a real hole, not a nicety: invites are addressed to an email, so without verification anyone can register an address they don't control and read that group's members and balances. Token hashed, single-use, 24 h expiry, rate-limited resend |
| SL-902 | Transactional email provider + SPF, DKIM and DMARC on your domain; verification, invite and notification templates; bounce handling | 2 | A verification mail to a Gmail address lands in **Inbox** with `dkim=pass; spf=pass; dmarc=pass`. Without this the app silently appears broken |
| SL-904 | **Guest tour:** "Explore the demo" logs the visitor straight into a seeded account — 3 members, expenses across all four split types, a non-trivial balance graph, a confirmed test settlement, a populated activity feed — reseeded nightly, reusing SL-705's seed script | 2 | No signup, no empty state; the simplification algorithm is visibly doing something within five seconds. Accounts created by real signups are **never** reset |
| SL-905 | Privacy policy, terms, contact address, and account deletion per ADR-014 | 2 | Deletion anonymises the user row and **retains** the postings, so other members' balances stay byte-identical; works from the UI with no database console. The policy is short, says data may be reset, and discloses the "Former member" placeholder |
| SL-805 | Playwright e2e in the pipeline: register → verify → group → invite → expense → settle (Sandbox) → balance zero | 2 | Runs on `main`, blocks the deploy on failure **[C20]** |
| SL-806 | Docs polish: README with diagram and GIF, Swagger link, perf report, ADR index, `docs/interview-notes.md`, resume bullets with real numbers | 2 | A stranger runs it in 10 minutes; every number traceable to a test or report |

**Demo:** open the URL in a clean browser, click "Explore the demo", settle a debt with a Razorpay
test card, then register properly, verify from the inbox, invite a second address, and delete the
account.

> **Story IDs are stable identifiers, not sprint numbers.** `SL-8xx` and `SL-9xx` are split across
> S6 and S9 because deployment moved forward. The sprint column in §2 is authoritative; the IDs
> stay fixed so references from `REVIEW_FINDINGS.md` and the issue tracker keep resolving.

---

## 13. Definition of Done — revised for this environment

Unchanged from `AGILE §7`, with three amendments forced by the no-local-Docker constraint:

- "Integration tests pass" means **in CI**. A green local run (unit + property + cloud-DB suite) is necessary but not sufficient — a story is Done only once CI is green on the PR.
- Local development runs against **managed dev data stores**. Never point a local run at the production database; the `prod` profile refuses a database URL it does not recognise.
- Every story that touches money carries at least one test that would fail if the invariant broke (sum-to-zero, sum-to-total, confirm-once, no-overpay). "Covered by existing tests" is not acceptable for a money path.

---

## 14. Risk register (updates to `AGILE §10.8`)

| ID | Risk | L | I | Mitigation |
|---|---|---|---|---|
| R1 | Scope creep | H | H | Portfolio cut line at S7, public-demo cut line at S9; S8 is the designated compressible sprint; everything else is backlog |
| R2 | Razorpay webhook unreachable from a dev machine | M | M | Tunnel, plus `simulate-webhook.sh` as the always-available path |
| R5 | **Free-tier data stores sleep, throttle or expire** | M | H | Cold-start tolerance in tests; nothing demo-critical lives only there; a documented 30-minute path to a different provider |
| R6 | **Local test runs need connectivity** | M | M | Unit + property tests stay fully offline; only the `@Tag("cloud")` suite needs the network |
| R7 | **No JDK/Maven/`gh` installed yet** | H | L | First task of SL-005; use `./mvnw` rather than a system Maven |
| R8 | Managed Kafka free tiers are scarce | M | M | Broker behind an interface; a `LocalEventBus` implementation keeps S7 demoable without one |
| R3 | VM too small | M | M | 4 GB instance, JVM heap caps |
| R4 | Time gaps / burnout | M | H | 13 pts, one day off per week, small stories |
| R9 | **A real UPI payment is triggered against a fake debt** | L | **High** | `UPI_INTENT` disabled in the deployed instance (SL-903); it is the only method that can move real money, and the data is fiction. Razorpay test mode cannot (`RUNNING_LIVE.md §2`) |
| R10 | **Invites and verification mails land in spam** | H | H | Domain-authenticated transactional email (SL-902), verified against a real Gmail inbox before launch. Without it the app appears broken for reasons you never see |
| R11 | **A visitor mistakes a test payment for a real one** | M | M | Non-dismissible banner, test-mode badge, "simulated payment — no funds were transferred" result copy (SL-903) |
| R12 | **A visitor leaves without seeing anything work** | H | M | The guest tour (SL-904) and the in-checkout test-instrument hint (SL-903). An empty app is a failed demo |
| R13 | Cost creep once it is always on | M | M | Fixed instance size, no autoscaling, per-account caps, billing alarm at 3× budget |
| R14 | Demo data lost | M | L | **Accepted.** Nightly dump, no PITR; nobody's rent depends on this. Real signups are never auto-reset, only the guest-tour account |

---

## 15. Backlog (deliberately not planned)

Archive-group · friend-level (non-group) balances · UPI QR on desktop (needed if desktop use turns out to matter) · AI natural-language expense draft · UPI QR on desktop · recurring expenses · receipt uploads · Google OAuth · Terraform + ECS Fargate · PWA · CSV export · group invite links by QR · multi-currency · OpenAPI contract-diff gate.

---

## 16. First three actions

SL-002 and SL-003 are done — the ADRs are written and the correction pass is applied. What is
left, in order:

1. **SL-001** — `git init`, the `backend/ frontend/ infra/ scripts/ .github/` skeleton, licence,
   `.gitignore`, branch protection on `main`. Everything else lands as a PR on top of this.
2. **SL-004** — provision the managed Postgres, Redis and SMTP accounts and write the real
   `.env.example`; SL-005 cannot be verified without them.
3. **SL-005** — install JDK 21 (this machine has none — `/usr/bin/java` is the macOS stub), then
   the backend skeleton: Maven Wrapper, Flyway `V1` from the corrected DDL, Actuator, problem+json,
   the correlation-id filter, the raw-body filter for `/webhooks/*`, and the ArchUnit baseline.
