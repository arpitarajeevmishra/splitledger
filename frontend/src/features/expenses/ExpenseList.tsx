import { useState } from 'react'
import { messageFor } from '../../api/problem'
import type { GroupDetail } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { formatDate } from '../../lib/format'
import { formatPaise } from '../../lib/money'
import { ExpenseForm } from './ExpenseForm'
import { useExpenses, useVoidExpense } from './useExpenses'

export function ExpenseList({ group }: { group: GroupDetail }) {
  const { data: expenses, isPending, isError, error } = useExpenses(group.id)
  const voidExpense = useVoidExpense(group.id)
  const [adding, setAdding] = useState(false)
  const [voidError, setVoidError] = useState<string | null>(null)

  const nameOf = (userId: string) =>
    group.members.find((member) => member.userId === userId)?.name ?? 'Someone'

  async function onVoid(expenseId: string, version: number) {
    setVoidError(null)
    try {
      await voidExpense.mutateAsync({ expenseId, version })
    } catch (failure) {
      // A stale version lands here, and the message tells them to reload rather than
      // leaving them clicking a button that keeps failing.
      setVoidError(messageFor(failure, 'Could not void that expense.'))
    }
  }

  return (
    <section className="rounded-lg border border-line bg-surface-1">
      <div className="flex items-center justify-between border-b border-line px-4 py-3">
        <h2 className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
          Expenses
        </h2>
        <Button onClick={() => setAdding(true)}>Add expense</Button>
      </div>

      {isPending && <p className="px-4 py-6 text-center text-sm text-ink-dim">Loading expenses…</p>}

      {isError && (
        <p role="alert" className="px-4 py-6 text-center text-sm text-negative">
          {messageFor(error, 'Could not load expenses.')}
        </p>
      )}

      {voidError !== null && (
        <p role="alert" className="px-4 pt-4 text-xs text-negative">
          {voidError}
        </p>
      )}

      {expenses?.length === 0 && (
        <div className="px-4 py-8 text-center">
          <p className="text-sm text-ink-muted">No expenses yet.</p>
          <p className="mt-1 text-xs text-ink-dim">
            Add one and everyone&rsquo;s balance updates straight away.
          </p>
        </div>
      )}

      {expenses !== undefined && expenses.length > 0 && (
        <ul>
          {expenses.map((expense) => {
            const voided = expense.status === 'VOIDED'
            return (
              <li
                key={expense.id}
                className="flex items-center gap-3 border-b border-line px-4 py-3 last:border-b-0"
              >
                <span className="min-w-0 flex-1">
                  <span
                    className={`block truncate text-sm ${
                      // Struck through rather than hidden: a voided expense is part of
                      // the history, and removing it from the list would make the
                      // activity feed reference something nobody can find.
                      voided ? 'text-ink-dim line-through' : 'font-medium text-ink'
                    }`}
                  >
                    {expense.description}
                  </span>
                  <span className="mt-0.5 block font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
                    {nameOf(expense.paidBy)} &middot; {formatDate(expense.expenseDate)}
                    {voided && ' · voided'}
                  </span>
                </span>
                <span
                  className={`tabular font-mono text-sm ${
                    voided ? 'text-ink-dim line-through' : 'text-ink'
                  }`}
                >
                  {formatPaise(expense.amountPaise)}
                </span>
                {!voided && (
                  <Button
                    variant="ghost"
                    disabled={voidExpense.isPending}
                    onClick={() => void onVoid(expense.id, expense.version)}
                  >
                    Void
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}

      <ExpenseForm group={group} open={adding} onClose={() => setAdding(false)} />
    </section>
  )
}
