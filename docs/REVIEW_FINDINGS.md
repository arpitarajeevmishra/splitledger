# SplitLedger — Documentation Review & Findings

**Reviewed:** `README.md`, `HLD.md`, `LLD.md`, `ROADMAP.md`, `AGILE.md`, `CICD_DOCKER_CLOUD.md` (2,294 lines)
**Date:** 2026-10-15
**Verdict:** The design is unusually strong for a portfolio project — ledger-first money model, integer paise, idempotency, outbox, real concurrency thinking. It is **not yet buildable as written**: one core contradiction, four missing pieces the documented UI flows depend on, and an environment assumption (local Docker + local Postgres) that this machine will not satisfy.

Nothing has been built. No `git` repo, no `backend/`, no `frontend/`, no `.env.example` — only the six design docs.

---

## 0. What has already been applied (2026-10-15)

The correction pass ran against the docs themselves, so `HLD.md`, `LLD.md`, `README.md`,
`CICD_DOCKER_CLOUD.md` and `ROADMAP.md` **already contain** the fixes below. The findings are kept
in full because they are the reasoning, and because several of them become code in Sprint 0.

**Applied to the docs**
- B1 — settlements now validate against net balances (`LLD §7.2`, ADR-010), with the simplified
  plan explicitly payable (`§6.3`) and the overpayment case defined.
- B2 — the five design docs plus these two now live in `docs/`; every README link resolves.
- B3 — the activity feed has a specification and a query (`LLD §6.4`); no `activity` table.
- B4 — `GET /settlements/{id}` added (`LLD §11.1`).
- B5 — token storage decided: access token in memory, refresh token in an `httpOnly;
  SameSite=Strict` cookie scoped to the refresh path (`LLD §12`, `§14.3`, ADR-011).
- B6 — the webhook path is fixed at `/webhooks/razorpay`, outside `/api/v1`, stated in three places.
- B7 — `README §6` rewritten for managed dev data stores; `CICD` reframed as CI-and-VM-only;
  ADR-012 recorded in `HLD §15`.
- C1–C22 — all applied at the document level: the idempotency `REQUIRES_NEW` marker, the amount
  and share and participant caps, `UNIQUE(type, source_id)`, the missing indexes and `CHECK`s,
  group currency, the `₹1` gateway minimum, outbox profile scoping, `INITIATED → EXPIRED` and the
  `expires_at` table, one locking convention (`If-Match`), the split Trivy jobs, the Playwright
  job, the nginx security headers and CSP, relative `/api/v1`, the `${OWNER}` templating, the
  dead deploy line removed, Swagger off in `prod`, and the Sprint-0 points total.
- M1, M2, M5, M6, M7, M9, M10 — invite tables and endpoints, notification-read endpoints, the
  friend-level-balances non-goal, the caps, invite rate limiting, CSP and `.dockerignore`.

**Still outstanding — these are Sprint 0 work, not documentation**
- ~~ADR files (SL-002)~~ — **done 2026-10-15:** all 15 are in [`adr/`](adr/README.md), including ADR-014 for the account-deletion policy behind `FR-11` (M3), plus ADR-013 (public-demo posture) and ADR-015 (in-process event bus).
- `git init`, the `backend/ frontend/ infra/ scripts/ .github/` skeleton, branch protection (SL-001).
- `.env.example`, `.nvmrc`, `.trivyignore`, `scripts/check-env.sh` as real files (SL-004, SL-006).
- JDK 21, and `gh` — neither is installed on this machine (SL-005).
- Managed Postgres / Redis / SMTP accounts provisioned (SL-004).
- M4 (leave/archive group), M11 (seed script, scheduled in SL-705), M12 (OpenAPI contract gate),
  M13 — deliberately in the backlog.

---

## 1. Blockers — settle these before writing code

| # | Blocker | Where | Fix |
|---|---|---|---|
| **B1** | **Simplified-plan settlements are impossible.** Simplification exists precisely to create transfers between people who have *no direct pairwise debt* (A owes B, B owes C → A pays C). But settlement creation validates `amountPaise <= outstanding(from → to)` from the **pairwise** query, so every simplified transfer is rejected with `SETTLEMENT_EXCEEDS_OUTSTANDING`. The frontend pre-fills the amount from the simplified plan, so the headline feature fails on its own happy path. | `LLD §7.2` vs `§6.3`, `§14.4` | Validate against **net balances**, not pairwise: `amount <= min(-net(from), net(to))` where `net(from) < 0 < net(to)`. Keep pairwise for display only. Write it as **ADR-010**. |
| **B2** | **README links are broken.** README points to `docs/HLD.md`, `docs/LLD.md`, … but all six files sit at the repo root. The repo layout in README §5 also claims `docs/`, `backend/`, `frontend/`, `infra/`, `.github/` — none exist. | `README §5`, `§7` | Create `docs/` and move the five design docs into it (README stays at root). |
| **B3** | **The activity feed has no data source.** `FR-9` requires it and `GET /groups/{id}/activity` is in the API table, but there is no `activity` table, no derivation rule, and no mention of it in any sprint story. | `HLD FR-9`, `LLD §11.1` | Decide: derive from `expenses` + `settlements` + `group_members` with a `UNION ALL` view, or add an `activity_log` table written by the same transaction. Deriving is cheaper; add it to the ledger/settlement sprints, not the end. |
| **B4** | **Settlement status polling has no endpoint.** `LLD §14.4` says "poll settlement status until `CONFIRMED`" and `SL-405` makes polling an acceptance criterion, but the API table has no `GET /settlements/{id}`. | `LLD §11.1` vs `§14.4` | Add `GET /settlements/{id}` (member-scoped). |
| **B5** | **Refresh-token storage is undecided.** `LLD §14.3` says "`httpOnly` cookie **or** in memory — choose one and record it as an ADR". This decision drives CORS, CSRF, the Axios interceptor, logout, and the Spring Security config. Sprint 1 cannot start without it. | `LLD §12`, `§14.3` | Decide in Sprint 0. Recommended: refresh token in `httpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth/refresh`, access token in memory only. Same-origin behind Caddy makes `SameSite=Strict` viable and removes the CSRF question. **ADR-011**. |
| **B6** | **Webhook path is ambiguous.** `POST /webhooks/razorpay` is listed inside the section whose base path is `/api/v1`, the Caddyfile routes `/webhooks/*` separately, and the README/HLD show `/webhooks`. A mismatch here means the Razorpay dashboard URL silently 404s. | `LLD §11.1`, `CICD §6.4` | Pick `/webhooks/razorpay` (outside `/api/v1`, no JWT filter, raw-body filter applied) and state it once in the LLD. |
| **B7** | **The documented local stack cannot run here.** Every "quick start" path depends on `docker compose up` (Postgres, Redis, Redpanda, Mailhog) and every integration test depends on Testcontainers, which needs a Docker daemon. Per your constraint, neither local Docker nor a local database is to be used. Also: **no JDK is installed** (`/usr/bin/java` is the macOS stub), **no Maven**, **no `gh`**, and Node is **24** while the docs pin 20. | `README §6`, `CICD §5`, `§6.3` | See §5 below — cloud-managed dev data stores, in-JVM test doubles locally, Docker/Testcontainers only inside CI and on the deploy VM. |

---

## 2. Correctness bugs and internal inconsistencies

| # | Issue | Where | Fix |
|---|---|---|---|
| C1 | **The `IDEMPOTENCY_IN_PROGRESS` (409) branch is unreachable.** The flow inserts `IN_PROGRESS` and runs the business logic "in the same DB transaction", so a concurrent duplicate never sees `IN_PROGRESS` — it blocks on the unique index until commit, then hits a unique violation. | `LLD §8` | Either insert the marker in a `REQUIRES_NEW` transaction (then 409 is real), or drop the 409 branch and document "duplicate blocks, then replays the stored response". Pick one; the error table advertises the 409. |
| C2 | **Max expense amount is not enforced anywhere.** `§4.1` caps a single expense at ₹10,00,000 (`100_000_000` paise) for overflow safety, but the DDL only has `CHECK (amount_paise > 0)` and no DTO constraint is mentioned. | `LLD §4.1`, `§3.2` | Add `CHECK (amount_paise BETWEEN 1 AND 100000000)` **and** a Bean Validation `@Max`. |
| C3 | **The overflow safety comment is false for SHARES.** `§4.3` comments `// safe: total <= 1e8, weight <= 1e4` — true for PERCENT basis points, but share counts are described only as "positive integers" with no upper bound. A large share count overflows `total * weight`. | `LLD §4.2`, `§4.3` | Cap shares (e.g. 1…1,000), cap participants (e.g. ≤ 50), and validate before allocation. Add a property test at the caps. |
| C4 | **Nothing prevents double-posting a ledger transaction.** `ledger_transactions(type, source_id)` has no unique constraint, so a retry or bug can post the same expense twice — exactly the drift the project exists to prevent. | `LLD §3.2` | `CREATE UNIQUE INDEX ... ON ledger_transactions(type, source_id) WHERE type <> 'REVERSAL';` — a cheap, database-level guarantee. |
| C5 | **Razorpay's ₹1 minimum order is not handled.** `settlements` only requires `amount_paise > 0`, so a ₹0.40 settlement is accepted and then fails at order creation with an opaque gateway error. | `LLD §7.4` | Enforce `amountPaise >= 100` for `method = RAZORPAY` at creation time with a clear error code; other methods keep `> 0`. |
| C6 | **The outbox publisher will run twice.** The consumer runs from the same image with `SPRING_PROFILES_ACTIVE=consumer`, and `@Scheduled(fixedDelay=500)` is not profile-scoped — so both containers poll. `SKIP LOCKED` keeps it safe, but it is accidental, not designed. | `HLD §6`, `LLD §10.3` | `@Profile("!consumer")` on the publisher, `@Profile("consumer")` on the listener. State it in the LLD. |
| C7 | **Sprint 0 arithmetic is wrong, and planned velocity exceeds declared capacity.** S0 stories sum to **7** points; the overview table says **6**. More importantly `AGILE §1` sets capacity at **~13 pts/sprint** while the roadmap plans 14, 14, 15, **16**, 14, 14 — every sprint is over capacity before anything goes wrong. | `ROADMAP §1`, `§2`, `AGILE §1` | Fix the total, and replan to ~13 (see `SPRINT_PLAN.md`). |
| C8 | **Two different optimistic-locking conventions.** `PUT /expenses/{id}` takes `version` in the body; `DELETE /expenses/{id}` takes `If-Match: version`. | `LLD §11.1` | Use `If-Match` for both (HTTP-correct), or the body for both. Do not ship both. |
| C9 | **Overpayment / post-settlement edits are undefined.** If an expense is voided or replaced *after* a settlement against that debt confirmed, outstanding can go negative. The pairwise query handles the sign, but `§7.2`'s rule, the UI and the simplified plan say nothing about "X overpaid Y by ₹Z". | `LLD §5.2`, `§6.2`, `§7.2` | Define it: negative outstanding is a reverse debt, shown as "Y owes X". Add a test: expense → settle → void expense → balances and UI remain coherent. |
| C10 | **Participants are not constrained to active group members.** `expense_splits.user_id` only has an FK to `users`, and nothing documented validates that `paid_by` and every participant are *active* members of that group. Same for `settlements.from_user` / `to_user` (mentioned in `§7.2`, not in the schema). | `LLD §3.2`, `§7.2` | Validate in the application layer on every write; add an explicit IDOR/cross-group test ("add an expense with a participant from another group → 422"). |
| C11 | The smoke test curls `/api/v1/health-check`, which does not exist in the API spec. Deployment would roll back on every release. | `CICD §3` vs `LLD §11.1` | Add a public `GET /api/v1/health-check`, or point the smoke test at `/actuator/health` only. |
| C12 | **`.env.example` is incomplete and inconsistent.** It has `DB_PASSWORD` but the prod compose needs `POSTGRES_PASSWORD` (two names for one secret); and it is missing `SMTP_*`, sandbox auto-confirm delay, `RAZORPAY_*` webhook path config, and `CORS_ALLOWED_ORIGINS`. The file does not exist yet at all. | `README §6`, `CICD §6.4` | One canonical name per secret; write the real `.env.example` in Sprint 0. |
| C13 | **The frontend API base URL is a build-time Vite variable and is never specified.** `COPY . . && npm run build` bakes it into the image, so one image cannot serve two environments. | `CICD §6.2` | Use **relative** `/api/v1` (same origin behind Caddy). No env var, one image everywhere. Record it. |
| C14 | Dead/incorrect code in the deploy step: `echo "OWNER=${GITHUB_REPOSITORY_OWNER,,}" >/dev/null` runs **on the remote host**, where that variable does not exist. The prod compose still has literal `ghcr.io/<owner>/…` placeholders. | `CICD §3`, `§6.4` | Delete the dead line; template the owner into the compose file or use an `${OWNER}` env var in `.env`. |
| C15 | **Trivy as a merge blocker will stop the project.** `scan-type: fs`, `severity: HIGH,CRITICAL`, `exit-code: 1` on a Spring Boot + npm tree reliably finds unfixable transitive CVEs. | `CICD §3`, `§4` | Keep the scan, start with `exit-code: 0` plus a `.trivyignore` review ritual; promote to blocking once the baseline is clean. Secret scanning should block from day one. |
| C16 | Node pinned to 20 in docs and CI; this machine has **24**. A mismatch produces "works locally, fails in CI". | `README §6`, `CICD §3` | Pin via `.nvmrc` + `engines`, and use the same version in CI. |
| C17 | The state machine has no `INITIATED → EXPIRED` edge, so a settlement created and abandoned before an order is ever made stays `INITIATED` forever and keeps appearing in the UI. | `LLD §7.1` | Add the edge; the expiry job sweeps both `INITIATED` and `PENDING`. Also define *when* `expires_at` is set (e.g. +15 min for gateway, +7 days for manual). |
| C18 | Missing indexes/constraints: no index on `ledger_postings(txn_id)` (every reversal lookup), none on `notifications(user_id, created_at DESC)` (the bell query), and `webhook_events.status` has no `CHECK` while every sibling column does. | `LLD §3.2` | Add all three. |
| C19 | Swagger UI is exposed and never disabled or protected in production. It publishes the full attack surface of a money API. | `CICD §7`, `README §6` | Disable springdoc in the `prod` profile, or put it behind Caddy basic auth. |
| C20 | Playwright is in the test strategy and in `LLD §14.5`, but the pipeline has no job that runs it. | `CICD §3`, `§5` | Add an e2e job on `main` (or nightly) against a compose-in-CI stack. |
| C21 | `expenses.currency CHAR(3)` exists but `expense_groups` has no currency column, and nothing validates expense currency against the group. Only INR is supported. | `LLD §3.2`, `HLD §1` | Add `expense_groups.currency CHAR(3) NOT NULL DEFAULT 'INR'`, validate equality, and reject anything but INR in v1 with a clear error. |
| C22 | Re-joining a group is impossible as modelled: `group_members` PK is `(group_id, user_id)` and departure is `left_at`, so a re-add collides. Balance queries also never consider membership history. | `LLD §3.2` | `UPSERT` that clears `left_at` on re-add; document that ledger history survives departure. |

---

## 3. Missing pieces (not wrong — absent)

| # | Gap | Why it matters |
|---|---|---|
| M1 | **No invite flow.** `POST /groups/{id}/members` adds "by email" and assumes the user already exists. Nothing covers inviting an unregistered email, and nothing asks the invitee's consent before their name/email is exposed to a group of strangers. | This is the single most-used action in a splitter app, and the main privacy exposure. |
| M2 | No endpoint to mark a notification read, though `notifications.read_at` exists. | The bell can never be cleared. |
| M3 | No account deletion or data export. | Legal exposure (DPDP Act 2023 in India / GDPR), and "delete my account" collides with an immutable ledger — needs a documented answer now, not later. |
| M4 | No leave-group, archive-group or delete-group. | Users accumulate dead groups with no exit. |
| M5 | No friend-level (non-group) balances. | Fine as scope, but say so explicitly — it is the first thing anyone compares to Splitwise. |
| M6 | No caps on group size or participant count per expense. | A 10,000-participant expense is a trivial DoS against the allocation + ledger write. |
| M7 | No rate limit on member-add / email-sending paths (only auth and settlement creation are covered). | Invite spam via your SMTP reputation. |
| M8 | The reconciliation job exports `ledger_reconciliation_drift` but no alert route is defined. | A gauge nobody watches is not a control. |
| M9 | No security headers / CSP; `nginx.conf` is three lines. | Cheap, expected, and a reviewer will look. |
| M10 | No `frontend/.dockerignore` (so `COPY . .` ships `node_modules`), while `backend/.dockerignore` is specified. | Slow, fat images. |
| M11 | No seed/demo-data script. | Every demo and every k6 run needs one; `k6 setup()` is hand-waved as a comment. |
| M12 | No OpenAPI contract snapshot/diff gate, though "Swagger updated" is in the DoD. | The DoD item is unverifiable. |
| M13 | ADRs 001–009 are summarised but the `docs/adr/` files do not exist, and three decisions still need ADRs: simplified-vs-pairwise validation (B1), refresh-token storage (B5), and the no-Docker local stack (B7). | |

---

## 4. Expense-splitter do's and don'ts

### 4.1 Money arithmetic
**Do**
- Keep integer paise end to end — DB `BIGINT`, Java `long`, JSON integer, Razorpay paise. (Already correct.)
- Parse user input as a **string** to paise (`"1,000.50"` → `100050`); never `parseFloat`.
- Make every split strategy sum **exactly** to the total, with a deterministic tie-break (largest remainder, then stable id order) so the same input always produces the same allocation.
- Decide and document who absorbs the leftover paisa, and show it in the UI ("₹33.34 for you, ₹33.33 each for others").
- Cap amounts, shares and participant counts so intermediate products cannot overflow.
- Property-test the invariants: sum equals total, nothing negative, error < 1 paisa, deterministic.

**Don't**
- Never use `float`, `double`, or JS `number` arithmetic for money; never round with `Math.round` on a rupee float.
- Never let the client send computed split amounts and trust them — recompute server-side from the inputs and compare.
- Never store percentages as decimals (use basis points) or distribute remainders randomly.
- Never display a rounded figure that disagrees with the stored paise.

### 4.2 Ledger, history and corrections
**Do**
- Keep postings immutable; correct with a reversal, edit by void + replace with a `replaces_expense_id` link. (Already correct.)
- Enforce "every transaction sums to zero" as a test, a DB-level guard, and a nightly reconciliation.
- Keep a full audit trail of who created, edited, voided and confirmed what, with timestamps.
- Show the group *why* a balance changed (activity feed) — unexplained balance movement is the #1 trust killer in splitter apps.

**Don't**
- Never `UPDATE` or `DELETE` a posting, and never hard-delete an expense, group or user that has ledger history.
- Never recompute history from a cache or a derived table and treat it as truth.
- Never silently change a balance a user has already settled against — if an edit touches a settled debt, surface it loudly to both parties.

### 4.3 Settlement semantics
**Do**
- Require the payer to be the authenticated user, forbid self-settlement, and require both parties to be active members.
- Make confirmation idempotent: confirming an already-confirmed settlement returns success and posts nothing.
- Treat the gateway webhook as the source of truth, the client callback as UX only, and run both through the same confirm use case.
- Re-validate outstanding at **confirm** time, not only at creation.
- Treat the simplified plan as a **suggestion**, and validate settlements against net balances so those suggestions are actually payable (B1).
- Handle overpayment explicitly as a reverse debt, with both parties notified.

**Don't**
- Never auto-settle or auto-mark a debt paid on the payer's word alone for cash/UPI — the receiver confirms. (Already correct.)
- Never allow a settlement that exceeds what is owed without an explicit, visible "overpayment" decision.
- Never let a member leave or be removed with a non-zero balance (already blocked) — and never resolve it by zeroing their balance.
- Never let two pending settlements for the same debt both confirm (re-check at confirm; the docs already note this trade-off).

### 4.4 Payments, PCI and Indian regulation
**Do**
- Use Razorpay **test keys only**, with a visible "Test mode — no real money moves" banner on every payment surface. (Already correct.)
- Send only the publishable `key_id` to the browser; keep `key_secret` and the webhook secret server-side.
- Verify webhook HMAC over the **raw request body** bytes with constant-time comparison, before any parsing or re-serialisation. (Already correct.)
- De-duplicate by provider event id, acknowledge unknown events with 200, and return 5xx on processing failure so the gateway retries.
- State plainly, in the README and in interviews, that a Razorpay order collects to **your** merchant account — the payee user never receives funds. The ledger records a *simulated* receipt.

**Don't**
- **Never** touch card numbers, CVV, UPI PIN or bank credentials — Checkout.js handles them; your server must never see or log them. Any custom card form puts you in PCI-DSS scope.
- Never implement user-to-user fund movement without Razorpay Route/Payouts, KYC and the matching regulatory posture. Holding user funds without a PPI licence is a regulated activity in India — do not build toward it, even as a stretch.
- Never charge a fee, take a cut, or describe the app as a payment service.
- Never log full webhook payloads, signatures, tokens or VPAs at INFO.
- Never trust an amount, payer or payee id from a webhook body — resolve the settlement via your stored `gateway_order_id` only. (Already correct.)
- Never ship production Razorpay keys in any environment of this project.

### 4.5 Privacy and the social surface
**Do**
- Require consent before adding someone to a group, or at minimum let them leave and be forgotten (M1).
- Scope every read to group membership, and return **404** (not 403) for other groups' resources so existence does not leak. (Already correct.)
- Expose only the minimum member profile inside a group (name + avatar); do not broadcast email, phone or UPI id to all members.
- Treat a member's VPA as sensitive: show it only to the counterparty of an actual settlement.
- Answer "delete my account" before launch: anonymise the user record, keep the postings (M3).

**Don't**
- Never let a non-member discover whether a group or a user exists (no "user not found" vs "no permission" distinction on lookups).
- Never put a user id, email, group id or amount in a URL query string, a notification subject line, or an analytics event.
- Never email the whole group the details of a two-party settlement.
- Never let one member's export include another member's PII beyond what the group already shows.

### 4.6 Operational
**Do** — idempotency keys on every money-creating endpoint, per-group locking for balance-affecting writes, rate limits on auth/settlement/invite, backups with a *tested* restore, and a drift metric that pages someone.
**Don't** — never let a Redis outage fail a read (degrade to the DB, already specified), never make migrations destructive in a forward-only pipeline, and never quote a performance number you did not measure.

---

## 5. Working without local Docker or a local database

The constraint invalidates `README §6` and the local half of `CICD §5`/`§6.3`. It does **not** invalidate the architecture: Docker still builds images in CI and runs them on the deploy VM — neither is this machine.

| Need | Documented | Replacement |
|---|---|---|
| Dev Postgres 16 | local Docker | **Managed Postgres free tier** (Neon / Supabase / Aiven). Real Postgres, so `FOR UPDATE`, `SKIP LOCKED`, `JSONB`, `TIMESTAMPTZ` and partial indexes all behave. Neon's branching gives a throwaway DB per sprint. |
| Dev Redis 7 | local Docker | **Upstash Redis free tier** over `rediss://`. Lettuce needs TLS enabled, nothing else. |
| Kafka broker | local Redpanda | Defer to the events sprint. Use **`spring-kafka-test` `EmbeddedKafkaBroker`** (in-JVM, no Docker) for tests, and a **managed Kafka free tier** (Confluent Cloud / Redpanda Cloud) for the deployed environment. Keep the publisher behind an interface so a `LocalEventBus` (Spring events) can stand in. |
| Mailhog | local Docker | **GreenMail** (in-JVM) for tests; **Mailtrap** free tier for dev and the demo. |
| Integration tests | Testcontainers (needs Docker) | Keep Testcontainers — but run it **only in CI**, where GitHub-hosted runners provide Docker. Locally, run unit + property tests, plus a `@Tag("cloud")` suite that points at the managed dev DB. |
| Prometheus + Grafana | local Docker profile | **Grafana Cloud free tier** via remote-write; drop the local profile. |
| Local `docker compose up` quick start | the whole of `README §6` | Rewrite: `./mvnw spring-boot:run` + `npm run dev` against cloud dev stores, with a one-command `.env` template. |

**Consequences to accept and write down**
- Local test runs are slower (network round-trip to the cloud DB) and need connectivity.
- The DoD must change: "integration tests pass" becomes "integration tests pass **in CI**"; a story is not Done on a green local run alone.
- Free tiers sleep, throttle and expire. Expect cold starts, and never put demo-critical data only there.
- Never point local dev at the production database. Separate credentials, separate project, and a `prod`-profile guard that refuses to run Flyway against a URL it does not expect.

**Toolchain to install first (none of it is a database or Docker)**
- JDK 21 (Temurin) — currently **absent**; `/usr/bin/java` is the macOS stub.
- Maven — absent (or use the Maven Wrapper `./mvnw`, which only needs the JDK: preferred).
- `gh` CLI — absent, needed for the GitHub Project board, issues and secrets.
- Node: pin to one version with `.nvmrc`; this machine has 24, the docs assume 20.

---

## 6. What is already right (keep it)

Integer paise with largest-remainder allocation and property tests · immutable double-entry ledger with reversals · per-group row lock plus optimistic `version` · idempotency keys in Postgres rather than Redis · raw-body HMAC verification with constant-time compare and event de-duplication · webhook as source of truth · transactional outbox with `SKIP LOCKED` · consumer-side `processed_events` de-duplication · 404-not-403 for cross-group reads · ArchUnit module boundaries · "quote only measured numbers" discipline · the honest scope disclaimer about Razorpay test mode.
