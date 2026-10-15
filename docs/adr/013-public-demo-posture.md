# ADR-013: Ship as a public demo: Razorpay test mode live, UPI intent disabled

**Status:** Accepted
**Date:** 2026-10-15
**Supersedes / relates to:** ADR-006, ADR-009

## Context

The project should be publicly usable, not just a repository. But moving real money *between
users* requires Razorpay Route or Payouts, a registered business entity and business KYC, and any
custody of funds requires a prepaid-instrument licence. Third-party person-to-person transfer is
among the most scrutinised models under India's payment-aggregator rules. That is a compliance
function with staff, not a sprint.

The important and counter-intuitive detail: **Razorpay test mode cannot move money — that is its
purpose — while a UPI intent link can.** A `upi://pay?pa=<vpa>&am=<amount>` deep link opens the
payer's real UPI app against a real payee. On an instance where the expense data is fictional, a
visitor could send real money from a real bank account to settle a debt that never existed. We
would not be in the flow, would not know, and could not reverse it.

## Decision

The deployed instance is a **public demo**, and the demo **is** production — one environment, no
staging.

- Settlement methods enabled: `RAZORPAY` (test mode), `CASH`, `SANDBOX`.
- `PAYMENTS_UPI_INTENT_ENABLED=false`. The adapter stays built and tested; it is simply not
  exposed.
- Demo status is stated unmissably in the UI: a non-dismissible banner, a "don't enter real
  financial information" notice at signup, Razorpay's published test instrument details rendered in
  the checkout, and result copy reading "simulated payment — no funds were transferred".
- Disposable data is an accepted, **published** property: nightly logical dump, no PITR, and data
  loss up to the last dump is stated rather than implied.

## Alternatives considered

- **Real settlement via UPI intent with real users.** Pros: genuinely useful; real money really
  moves, peer-to-peer, never through our system; no custody and no PCI scope either. Cons: requires
  the full real-user posture — PITR, rehearsed restores, data export, wind-down obligations — and
  carries the risk above for any fictional data. Rejected **for now**, and it is one flag away.
- **Razorpay live mode.** Not available without a business entity and KYC, and user-to-user routing
  is a regulated activity regardless.
- **Keep it local-only.** Pros: zero risk. Cons: no live URL, and webhook development stays behind
  a tunnel indefinitely.

## Consequences

- **Positive:** no regulatory exposure, no PCI-DSS scope, no custody of funds. Data-safety
  requirements collapse to a nightly dump, removing the most expensive part of the launch plan. A
  stable public HTTPS URL makes webhook work far easier — which is why deployment now **precedes**
  the Razorpay sprint.
- **Negative:** the product cannot be used to track real money, so the "real users" story is
  unavailable. Honesty becomes a build requirement rather than a disclaimer. A guest tour becomes
  necessary, because an empty demo teaches a visitor nothing.
- **Follow-up:** if this is ever reversed, flip the UPI flag and adopt the real-user requirements
  as a set — they are not individually optional.
