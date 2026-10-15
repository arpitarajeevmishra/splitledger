# ADR-003: Immutable ledger: corrections by reversal, edits by replace

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-002, ADR-014

## Context

Users will edit and delete expenses. If a posting can be updated in place, the system can never
answer "why did my balance change last Tuesday?", and a bug silently rewrites financial history
with no trace. Shared-expense apps live or die on whether members trust the numbers, and trust
comes from being able to explain every movement.

## Decision

`ledger_postings` is append-only. Nothing is ever `UPDATE`d or `DELETE`d.

- **Void** inserts a reversal transaction whose postings are the exact negation of the original,
  with `reverses_txn_id` set, and flips `expenses.status` to `VOIDED`.
- **Edit** is void-plus-create in one transaction: the new expense carries
  `replaces_expense_id` pointing at the old one.
- Every ledger transaction sums to exactly **0**, asserted in tests and re-derived nightly. A
  reconciliation job computes balances from postings and independently from active expenses plus
  confirmed settlements, and exports `ledger_reconciliation_drift`, which must always be zero.
- `UNIQUE (type, source_id) WHERE type <> 'REVERSAL'` makes double-posting a business fact
  impossible at the database level.

## Alternatives considered

- **Mutate the expense and recompute balances.** Pros: fewer rows, less code. Cons: no audit
  trail, no explanation of a change, and a logic bug corrupts history invisibly.
- **Soft-delete flag only, no reversal.** Pros: simpler than reversals. Cons: every balance query
  has to special-case status, and a *correction* (as opposed to a deletion) is not representable
  at all.

## Consequences

- **Positive:** every balance is explainable from an immutable chain. The activity feed and the
  reconciliation job fall out of this decision rather than needing their own machinery. A defect
  can be diagnosed after the fact because the evidence still exists.
- **Negative:** more rows than strictly necessary. "Delete my account" cannot delete postings,
  which is why ADR-014 exists.
- **Follow-up:** the void-restores-balances-exactly test is the single most important regression
  test in the project.
