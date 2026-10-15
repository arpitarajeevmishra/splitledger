# Decision Log

**Purpose:** every decision taken while building SplitLedger, with the trade-off that was
knowingly accepted. Chronological, append-only.

**How this differs from [`adr/`](adr/README.md):** an ADR is a formal, one-per-decision record
with full context and alternatives, reserved for choices that shape the architecture. This log is
the complete running list — including the many small calls that do not warrant an ADR but that
someone (including you, in three months) will otherwise have to reverse-engineer from a diff. Where
a decision has an ADR, this log states the trade-off in one line and links out.

**The rule for this file:** if a decision cost more than a minute of thought, or if a reasonable
person would have chosen differently, it belongs here — *with the downside written down*. A log
that only records the upside is marketing, not engineering.

Each entry: **what was decided**, *why*, and **the trade-off accepted**.

---

## Phase 0 — Design (2026-10-14 → 15)

The fifteen architectural decisions, with the cost of each. Full reasoning in [`adr/`](adr/README.md).

| # | Decision | Trade-off accepted |
|---|---|---|
| [ADR-001](adr/001-modular-monolith.md) | Modular monolith, boundaries enforced by ArchUnit | No independent deployability; one bad deploy takes everything down |
| [ADR-002](adr/002-money-as-integer-paise.md) | Money as `long` paise | Every display path needs explicit formatting; multi-currency later needs a real `Money` type, not just a column |
| [ADR-003](adr/003-immutable-ledger.md) | Append-only postings; void by reversal, edit by replace | More rows; "delete my account" cannot delete postings, which forced ADR-014 |
| [ADR-004](adr/004-per-group-locking.md) | Per-group row lock + optimistic `version` | A very active group serialises its own writes |
| [ADR-005](adr/005-transactional-outbox.md) | Transactional outbox | Up to one poll interval of latency; consumers *must* be idempotent |
| [ADR-006](adr/006-payment-provider-port.md) | `PaymentProvider` port | The port is a lowest common denominator, so gateway fields leak onto the aggregate as nullables |
| [ADR-007](adr/007-idempotency-keys-in-postgres.md) | Idempotency keys in PostgreSQL | One extra write on the hot path, plus a purge job to own |
| [ADR-008](adr/008-trunk-based-development.md) | Trunk-based, short-lived branches | Needs the discipline to keep stories small enough to land in two days |
| [ADR-009](adr/009-single-host-docker-compose.md) | Single host + Compose | You own OS patching; single point of failure |
| [ADR-010](adr/010-net-balance-settlement-validation.md) | Settlements validated against **net** balances | A coarser limit than pairwise: you may pay someone you owe nothing directly, so the UI must show the real ceiling |
| [ADR-011](adr/011-token-storage.md) | Access token in memory, refresh in an `httpOnly` cookie | **Requires** same-origin deployment, which makes the relative `/api/v1` base URL load-bearing |
| [ADR-012](adr/012-no-local-database-or-docker.md) | Managed dev stores; Testcontainers in CI only | Local integration tests need connectivity and are slower; Definition of Done changes to "CI green" |
| [ADR-013](adr/013-public-demo-posture.md) | Public demo; Razorpay test mode live; UPI intent off | The product cannot track real money, so the "real users" story is unavailable |
| [ADR-014](adr/014-account-deletion-anonymises.md) | Deletion anonymises, postings retained | A "Former member" placeholder remains, and must be disclosed in the privacy policy |
| [ADR-015](adr/015-in-process-event-bus.md) | Deployed runtime drops the broker and Redis | Deployment diagram differs from the architecture diagram; both must say so |

**D-00 · The documentation was corrected before any code was written.**
*Why:* the review found a contradiction (ADR-010) that would have surfaced in Sprint 5 with the UI
already built on top of it, plus 21 smaller defects.
**Trade-off:** two days spent on documents with nothing running. Cheaper than discovering ADR-010
in week six.

---

## Phase 1 — Sprint 0 build (2026-10-15)

### D-01 · Design docs moved into `docs/`, README links repaired
*Why:* the README already claimed a `docs/` directory and linked into it; the files were at the
repository root, so every documentation link was broken.
**Trade-off:** none. This was simply a defect (finding B2).

### D-02 · oxlint kept instead of ESLint
*Why:* the current Vite React-TS template ships oxlint, not ESLint. It needed no configuration and
is substantially faster.
**Trade-off:** diverges from the documented stack, and oxlint's rule coverage is narrower than a
tuned ESLint setup — notably for React Hooks rules. Accepted for now; the docs were updated to
match rather than the tooling bent to match the docs. Revisit if hook bugs start slipping through.

### D-03 · TypeScript `strict` enabled, plus `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`
*Why:* the template did not enable them. This application does arithmetic on money.
**Trade-off:** more friction when writing code, particularly around array access and optional
fields. That is the point.

### D-04 · Java 27 instead of Java 21 — *user decision*
*Why:* JDK 27 was what got installed, and the alternative was installing a second JDK.
**Trade-off, accepted with eyes open:** Java 27 is a **non-LTS interim release** with roughly six
months of support, where 21 and 25 are LTS. Spring Boot's tested baseline is lower, and tooling
that manipulates bytecode (ByteBuddy, Mockito, JaCoCo) historically lags new class-file versions.
Mitigation: the toolchain was verified empirically rather than assumed — see D-05 and D-09 — and
CI is pinned to the same version so there is no local/CI drift. **Revisit at the next LTS**; moving
to 25 or 29 later is a property change plus a CI bump.

### D-05 · Spring Boot 4.1.1, not 3.x
*Why:* not a preference — 4.1.1 is the current GA release, and 4.x is what the Initializr produces
for Java 27.
**Trade-off:** the docs were written against Boot 3.x, so several assumptions changed and had to be
chased down: starters renamed (`spring-boot-starter-flyway`, `spring-boot-starter-kafka`),
springdoc needs the **3.x** line, and Spring Security 7 is lambda-DSL only. Less community material
exists for 4.x than 3.x, so some answers will have to come from reference docs rather than Stack
Overflow.

### D-06 · The Initializr's `bootVersion` id is not the Maven coordinate
*Why:* not a decision — a trap worth recording. `start.spring.io` lists `4.1.1.RELEASE`, but the
artifact in Maven Central is `4.1.1`. The generated pom therefore did not resolve at all.
**Lesson applied:** library versions are now taken from Maven Central's `maven-metadata.xml`
rather than from a generator's UI label.

### D-07 · No raw-body caching filter; the webhook controller takes `@RequestBody byte[]`
*Why:* the LLD specified a servlet filter to cache the raw body for HMAC verification. Spring will
hand a controller the exact bytes directly, which is less machinery and has no ordering subtleties.
`ContentCachingRequestWrapper` only caches *as the stream is consumed*, which is a sharp edge in
precisely this use case.
**Trade-off:** we cannot have both the raw bytes and Spring-parsed JSON for free — the webhook
handler parses the payload itself after verifying the signature. That is fine, and arguably safer:
nothing is deserialised before the signature is checked.

### D-08 · Surefire runs unit tests; Testcontainers tests are `*IT` under Failsafe
*Why:* ADR-012 means there is no Docker locally, so `./mvnw test` has to pass with no daemon and no
network. The generated `SplitledgerApplicationTests` needed containers and was renamed
`SplitledgerApplicationIT`.
**Trade-off:** integration tests cannot be run locally at all. The gap is real and is the reason
the Definition of Done is "CI green", not "tests pass on my machine".

### D-09 · JaCoCo and its coverage gate deferred to Sprint 3
*Why:* two reasons. There is no domain or application code yet, so an 80% gate would measure
configuration classes and prove nothing. And JaCoCo's support for new class-file versions lags, so
on Java 27 (D-04) it is a likely source of an unexplained build failure.
**Trade-off:** no coverage enforcement until SL-301 lands, and the CI quality-gate table currently
overstates what runs. Added in the sprint where the money code gives it something worth measuring.

### D-10 · ArchUnit's empty-rule failure exempted per rule, not globally
*Why:* the `..ledger..` rule matches no classes until Sprint 3, and ArchUnit fails empty rules by
default — correctly, since that is how a mistyped package name gets caught. The exemption is
`.allowEmptyShould(true)` on that one rule, not `archRule.failOnEmptyShould=false` in a properties
file.
**Trade-off:** a line of scaffolding to remember to delete (noted in the code, due in SL-302)
instead of silently losing typo protection across all nine rules.

### D-11 · Testcontainers image tags pinned
*Why:* the generated configuration used `postgres:latest`, `redis:latest` and
`apache/kafka-native:latest`. A `latest` tag makes a suite that passes today and fails on an
unrelated morning.
**Trade-off:** the pins need periodic bumping, which Dependabot handles.

### D-12 · `V1__init.sql` stays faithful to the reviewed DDL; S9's columns get their own migration
*Why:* email verification (`email_verified_at`) and account deletion (`deleted_at`) belong to
Sprint 9 stories. Migrations are forward-only and additive, so adding them later is a trivial `V2`.
**Trade-off:** `users` will be altered again rather than being right first time. Preferable to
building schema for stories that have not been specified yet.

### D-13 · Constructor injection, never `@Value` on a field
*Why:* field injection hides dependencies and cannot be used from a test without reflection. The
first `SecurityConfig` draft used a `@Value` field and ArchUnit's `NO_CLASSES_SHOULD_USE_FIELD_INJECTION`
rejected it — the rule earning its place on day one.
**Trade-off:** marginally more verbose.

### D-14 · Flags default to the safe value
*Why:* `REGISTRATION_ENABLED=false` and `PAYMENTS_UPI_INTENT_ENABLED=false` in `.env.example` and
`application.yml`. The app can be deployed in Sprint 6 while the signup hole that email
verification closes (SL-901) is simply not exposed, and the one payment method that can reach a
real bank account is off wherever data is fictional.
**Trade-off:** a forgotten flag means a feature silently absent in production. Mitigated by making
both flags explicit in the launch stories' acceptance criteria.

### D-15 · CI jobs for code that does not exist yet are gated by a `detect` job
*Why:* the pipeline is written in full, but the backend, e2e and publishing jobs would fail until
`backend/pom.xml` and `infra/docker-compose.yml` exist. A job-level `if: hashFiles(...)` cannot
work — nothing is checked out at job level — so a small `detect` job probes and the others gate on
its output.
**Trade-off:** one extra job on every run, and a gate that will silently skip the backend job if
the pom ever moves. Preferred over a pipeline that is red for a week for known reasons.

### D-16 · Trivy splits into a blocking secret scan and a reporting vulnerability scan
*Why:* a committed secret is always our bug and always fixable, so it blocks. HIGH/CRITICAL
vulnerabilities in a Spring Boot and npm tree are frequently unfixable transitives, and a pipeline
that is red for reasons you cannot act on is a pipeline you stop reading.
**Trade-off:** real vulnerabilities can land on `main` without blocking. Promoted to blocking once
the baseline is clean, with a curated `.trivyignore`.

### D-17 · Git identity set per repository, with the name inferred
*Why:* no global git identity existed, so there was nothing to conflict with; a scoped
`includeIf` keeps work and personal separate as the machine accumulates both.
**Trade-off / open:** the commit name was inferred from the email address and has **not been
confirmed**. Two commits to amend now, a history rewrite later.

### D-18 · A commit that mixed two stories was split rather than left
*Why:* a `git add -A` swept the CI files into the SL-006 commit. The project's own working
agreements say one story per commit.
**Trade-off:** rewrote three unpushed commits. Free now; would have been a `git revert` and an
apology after pushing.

---

## Open decisions

| # | Question | Blocked on |
|---|---|---|
| O-1 | Confirm the commit author name and LICENSE holder | You (D-17) |
| O-2 | Which managed Postgres, and is its free-tier backup retention acceptable | Provisioning an account |
| O-3 | Whether to move to an LTS JDK at the next release | Revisit after Java 29 (D-04) |
| O-4 | Whether oxlint's React Hooks coverage is sufficient | Experience during S1–S4 (D-02) |
| O-5 | Promote the Trivy vulnerability scan to blocking | A clean baseline (D-16) |
