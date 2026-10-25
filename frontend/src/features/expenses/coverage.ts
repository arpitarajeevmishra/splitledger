import type { Debt, Expense, ExpenseSplit } from '../../api/types'

/** What can truthfully be said about one person's share of one expense. */
export type Coverage =
  | { kind: 'PAID_IT' }
  | { kind: 'REVERSED' }
  | { kind: 'SQUARE' }
  | { kind: 'OWES'; paise: number }
  | { kind: 'UNKNOWN' }

/**
 * Whether this person's share of this expense is still hanging.
 *
 * <p><b>There is no such thing in this system as a paid expense,</b> and a badge claiming
 * one would be invented. The ledger records who owes what and never which payment covered
 * which expense: a settlement moves a <em>balance</em>, and simplification may route it
 * through a third person who was never on the expense at all (ADR-010). Applying payments
 * to expenses oldest-first would produce a plausible per-expense status that silently
 * rewrote itself the moment anyone added an expense.
 *
 * <p>What *is* a fact is whether two people are square. If nobody who shared an expense
 * still owes whoever paid it, then nothing from that expense is outstanding -- that
 * follows from the balance being zero rather than being guessed at. When they do still owe,
 * the figure is their whole standing with the payer, which is why it is labelled as such
 * instead of being attributed to this row.
 *
 * <p>A voided expense is the one case with a per-expense answer: its postings were
 * reversed, so nobody owes anything from it regardless of what the balances say.
 */
export function coverageOf(
  split: ExpenseSplit,
  expense: Expense,
  pairwise: Debt[] | undefined,
): Coverage {
  if (expense.status === 'VOIDED') {
    return { kind: 'REVERSED' }
  }
  // Their own share of their own expense is not a debt to themselves.
  if (split.userId === expense.paidBy) {
    return { kind: 'PAID_IT' }
  }
  if (pairwise === undefined) {
    return { kind: 'UNKNOWN' }
  }
  const owed = pairwise.find((debt) => debt.from === split.userId && debt.to === expense.paidBy)
  // Absent from the pairwise list means nothing outstanding between them: the query omits
  // pairs that cancel out, so a missing row and a zero row mean the same thing.
  return owed === undefined || owed.amountPaise === 0
    ? { kind: 'SQUARE' }
    : { kind: 'OWES', paise: owed.amountPaise }
}
