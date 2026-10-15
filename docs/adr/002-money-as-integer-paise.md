# ADR-002: Money as integer paise (`BIGINT` / `long`)

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-003

## Context

Binary floating point cannot represent 0.1, so any `double` arithmetic on rupees eventually
produces a balance that is off by a fraction of a paisa — and in a ledger that difference
accumulates and is visible to users. Splitting ₹100 three ways must lose nothing and invent
nothing. Razorpay's API also expresses amounts in paise, so paise is the natural boundary unit.

## Decision

`long` paise everywhere: `BIGINT` in PostgreSQL, `long` in Java, an integer in JSON, and
string-based parsing in the browser (`money.ts` parses `"1,000.50"` to `100050` without ever
constructing a float). A `Money` value object owns all arithmetic and validation.

Amounts are bounded to `1..100_000_000` paise (₹10,00,000), enforced **twice**: a DB `CHECK`
constraint and a Bean Validation annotation. Share counts are capped at 1,000 and participants at
50, so the `total * weight` product inside the largest-remainder allocation cannot overflow.

## Alternatives considered

- **`BigDecimal`.** Pros: decimal-exact, handles arbitrary scale, the conventional enterprise
  answer. Cons: every operation needs an explicit scale and `RoundingMode`, so rounding policy ends
  up scattered across the codebase; slower; and it serialises to JSON in a form that invites float
  parsing on the client. For a single currency with a fixed 2-decimal minor unit, it buys nothing.
- **`double`.** Rejected outright; this is the defect the project exists to avoid.
- **Rupees stored with 2 decimal places.** Same failure as `double` the moment arithmetic happens.

## Consequences

- **Positive:** an entire class of bug ceases to exist. The unit matches the gateway, so no
  conversion happens at the boundary. Property tests can assert *exact* equality rather than
  tolerance, which is what makes the largest-remainder proof meaningful.
- **Negative:** every display path needs explicit formatting, and forgetting it shows "90000"
  instead of "₹900.00". Multi-currency later needs a currency-aware `Money`, not just the existing
  currency column.
- **Follow-up:** the caps must be tested *at* the boundary values, not merely below them.
