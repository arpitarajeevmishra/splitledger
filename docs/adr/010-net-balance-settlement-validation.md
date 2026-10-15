# ADR-010: Settlements are validated against net balances, not pairwise debt

**Status:** Accepted
**Date:** 2026-10-15
**Supersedes / relates to:** ADR-003

## Context

Debt simplification exists precisely to produce transfers between people who have **no direct
pairwise debt**: if A owes B and B owes C, the simplified plan tells A to pay C. The first draft of
the design validated settlement creation against the *pairwise* outstanding figure.

Those two rules contradict each other. Every transfer taken from the simplified plan would be
rejected with `SETTLEMENT_EXCEEDS_OUTSTANDING` — and since the settle-up dialog pre-fills its
amount from that very plan, the project's headline feature would have failed on its own happy path.
This was caught in review before implementation; it would otherwise have surfaced in the settlement
sprint with the UI already built on top of it.

## Decision

Validate against **net** balances:

```
require net[from] < 0        -- the payer must actually owe the group
require net[to]   > 0        -- the payee must actually be owed by the group
limit = min(-net[from], net[to])
require 0 < amountPaise <= limit
```

The identical check re-runs at **confirm** time, which is what stops two pending settlements for
the same debt from both completing. Pairwise figures remain the **display** model ("you owe Rahul
₹300") and are never the authorisation model. Overpayment created by voiding or replacing an
expense *after* a settlement confirmed is a legal, visible reverse debt, not an error.

## Alternatives considered

- **Validate pairwise.** This is the defect being fixed: it breaks simplification entirely.
- **Validate nothing; let balances go negative freely.** Pros: simplest possible rule. Cons:
  permits paying someone you owe nothing, which destroys confidence in the numbers — the one thing
  this app cannot afford to lose.
- **Subtract pending settlements from the limit.** Deferred: it needs a reservation concept with
  its own expiry semantics. The confirm-time re-check already covers the double-spend case with a
  clear error, and the trade-off is documented rather than hidden.

## Consequences

- **Positive:** simplified transfers are payable, so the feature works as designed. One rule
  governs both direct and simplified settlement, instead of two rules that can disagree.
- **Negative:** the limit is coarser than pairwise. A user may pay someone they owe nothing
  *directly*, provided they owe the group and that person is owed by it — which is exactly what
  simplification asks for, but it must be explained in the UI rather than left to surprise people.
- **Follow-up:** the settle-up dialog must display the real ceiling `min(-net[from], net[to])`, so
  a user never discovers the limit by receiving a 422.
