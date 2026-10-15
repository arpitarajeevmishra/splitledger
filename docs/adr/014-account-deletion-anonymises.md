# ADR-014: Account deletion anonymises the user and retains postings

**Status:** Accepted
**Date:** 2026-10-15
**Supersedes / relates to:** ADR-003

## Context

ADR-003 makes ledger postings immutable, and those postings are **shared** facts: deleting one
member's postings silently changes other members' balances and breaks the sum-to-zero invariant.
But people must be able to leave, and the service collects real names and real email addresses
regardless of how fictional the expense amounts are.

## Decision

Deletion **anonymises** rather than removes. The user row is cleared — name, a non-reversible
placeholder email, password hash and VPA removed, `deleted_at` set — while expenses, splits,
postings and settlements are retained unchanged. The person renders as **"Former member"**
everywhere they appear.

Also on deletion: open invites are revoked, and all sessions and refresh tokens are destroyed. The
whole flow works from the UI, with no database console. A group left with no active members may be
hard-deleted in full.

## Alternatives considered

- **Hard-delete the user and cascade.** Pros: the cleanest possible privacy answer. Cons:
  destroys other members' correct balances and violates the ledger invariants the project is built
  on.
- **Hard-delete and rewrite history to rebalance.** Cons: mutating an immutable ledger, which
  contradicts ADR-003 and makes the audit claim false.
- **Refuse deletion.** Not acceptable where personal data is collected.

## Consequences

- **Positive:** other members' balances stay correct and auditable, and the personal data
  genuinely goes.
- **Negative:** a residual "Former member" placeholder remains, which must be **disclosed in the
  privacy policy** rather than glossed over.
- **Follow-up:** the anonymisation path needs its own test asserting that every other member's
  balance is byte-identical afterwards and that the sum-to-zero invariant still holds.
