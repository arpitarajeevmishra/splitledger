import { messageFor } from '../../api/problem'
import type { Expense, ExpenseSplit, GroupDetail } from '../../api/types'
import { Dialog } from '../../components/ui/Dialog'
import { coverageOf } from './coverage'
import { formatDate } from '../../lib/format'
import { formatPaise } from '../../lib/money'
import { useBalances, useExpense } from './useExpenses'

/** The input behind a share, when it was not simply an equal split. */
function inputHint(expense: Expense, split: ExpenseSplit): string | null {
  if (split.inputValue === null) {
    return null
  }
  if (expense.splitType === 'PERCENT') {
    // Basis points on the wire, so 2500 is 25%.
    return `${(split.inputValue / 100).toFixed(split.inputValue % 100 === 0 ? 0 : 2)}%`
  }
  if (expense.splitType === 'SHARES') {
    return split.inputValue === 1 ? '1 share' : `${split.inputValue} shares`
  }
  // For EXACT the input *is* the amount already on the row.
  return null
}

export function ExpenseDetail({
  group,
  expenseId,
  onClose,
}: {
  group: GroupDetail
  expenseId: string | null
  onClose: () => void
}) {
  const { data: expense, isPending, isError, error } = useExpense(expenseId)
  // The same query key the balances panel already holds, so opening this fetches nothing
  // the page has not fetched anyway.
  const { data: balances } = useBalances(group.id)

  const nameOf = (userId: string) =>
    group.members.find((member) => member.userId === userId)?.name ?? 'Someone'

  const others = (expense?.splits ?? []).filter((split) => split.userId !== expense?.paidBy)
  const owing = others.filter(
    (split) =>
      expense !== undefined && coverageOf(split, expense, balances?.pairwise).kind === 'OWES',
  ).length

  return (
    <Dialog
      open={expenseId !== null}
      onClose={onClose}
      title={expense?.description ?? 'Expense'}
      description={
        expense === undefined
          ? undefined
          : `${formatPaise(expense.amountPaise)} paid by ${nameOf(expense.paidBy)} on ${formatDate(expense.expenseDate)}`
      }
    >
      {isPending && <p className="text-sm text-ink-dim">Loading expense…</p>}

      {isError && (
        <p role="alert" className="text-sm text-negative">
          {messageFor(error, 'Could not load that expense.')}
        </p>
      )}

      {expense !== undefined && (
        <>
          <p
            role="status"
            className={`rounded border px-3 py-2 text-sm ${
              expense.status === 'VOIDED'
                ? 'border-line bg-surface-2 text-ink-muted'
                : balances === undefined
                  ? 'border-line bg-surface-2 text-ink-dim'
                  : owing === 0
                    ? 'border-line bg-surface-2 text-ink'
                    : 'border-line bg-surface-2 text-negative'
            }`}
          >
            {expense.status === 'VOIDED'
              ? 'Voided — these shares were reversed, so nobody owes anything from this expense.'
              : balances === undefined
                ? 'Checking who is still square…'
                : owing === 0
                  ? `Everyone who shared this is square with ${nameOf(expense.paidBy)}.`
                  : `${owing} of ${others.length} still owe ${nameOf(expense.paidBy)}.`}
          </p>

          <ul className="mt-4 divide-y divide-line border-y border-line">
            {expense.splits.map((split) => {
              const coverage = coverageOf(split, expense, balances?.pairwise)
              const hint = inputHint(expense, split)
              return (
                <li key={split.userId} className="flex items-start justify-between gap-3 py-2.5">
                  <span className="min-w-0 flex-1 truncate text-sm text-ink">
                    {nameOf(split.userId)}
                    {hint !== null && (
                      <span className="ml-1.5 font-mono text-[11px] text-ink-dim">({hint})</span>
                    )}
                  </span>
                  {/* Only the outstanding row is coloured. Green for "settled" was the
                      obvious choice and measures 2.54:1 on the light panel -- below AA for
                      text this size -- and a ledger reads better when the thing needing
                      attention is the thing that stands out. */}
                  {/* Stacked rather than a third column: "owes <amount> in all" does not
                      fit beside a name and a figure at phone width, and the amount is the
                      thing being qualified anyway. */}
                  <span className="shrink-0 text-right">
                    <span className="tabular block font-mono text-sm text-ink">
                      {formatPaise(split.owedPaise)}
                    </span>
                    <span
                      className={`mt-0.5 block font-mono text-[11px] tracking-[0.04em] uppercase ${
                        coverage.kind === 'OWES' ? 'text-negative' : 'text-ink-dim'
                      }`}
                    >
                      {coverage.kind === 'PAID_IT' && 'paid it'}
                      {coverage.kind === 'REVERSED' && 'reversed'}
                      {coverage.kind === 'SQUARE' && 'settled'}
                      {coverage.kind === 'OWES' && `owes ${formatPaise(coverage.paise)} in all`}
                      {coverage.kind === 'UNKNOWN' && '—'}
                    </span>
                  </span>
                </li>
              )
            })}
          </ul>
        </>
      )}
    </Dialog>
  )
}
