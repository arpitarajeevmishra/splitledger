import { messageFor } from '../../api/problem'
import type { GroupDetail } from '../../api/types'
import { formatPaise } from '../../lib/money'
import { useBalances, useSimplifiedPlan } from './useExpenses'

export function BalancesPanel({ group }: { group: GroupDetail }) {
  const { data: balances, isPending, isError, error } = useBalances(group.id)
  const { data: plan } = useSimplifiedPlan(group.id)

  const nameOf = (userId: string) =>
    group.members.find((member) => member.userId === userId)?.name ?? 'Someone'

  return (
    <section className="rounded-lg border border-line bg-surface-1">
      <div className="border-b border-line px-4 py-3">
        <h2 className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
          Balances
        </h2>
      </div>

      {isPending && <p className="px-4 py-6 text-center text-sm text-ink-dim">Loading balances…</p>}

      {isError && (
        <p role="alert" className="px-4 py-6 text-center text-sm text-negative">
          {messageFor(error, 'Could not load balances.')}
        </p>
      )}

      {balances !== undefined && (
        <ul>
          {balances.net.map((balance) => (
            <li
              key={balance.userId}
              className="flex items-center justify-between border-b border-line px-4 py-2.5 last:border-b-0"
            >
              <span className="min-w-0 truncate text-sm text-ink">{nameOf(balance.userId)}</span>
              <span
                className={`tabular font-mono text-sm ${
                  balance.settled
                    ? 'text-ink-dim'
                    : balance.netPaise > 0
                      ? 'text-positive'
                      : 'text-negative'
                }`}
              >
                {balance.settled ? 'settled up' : formatPaise(balance.netPaise)}
              </span>
            </li>
          ))}
        </ul>
      )}

      {plan !== undefined && (
        <div className="border-t border-line px-4 py-3">
          <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
            Settle up
          </p>
          {plan.settled ? (
            <p className="mt-2 text-sm text-ink-muted">Nobody owes anybody.</p>
          ) : (
            <>
              <ul className="mt-2 flex flex-col gap-1">
                {plan.transfers.map((transfer) => (
                  <li
                    key={`${transfer.from}-${transfer.to}`}
                    className="flex items-center justify-between text-sm"
                  >
                    <span className="min-w-0 truncate text-ink-muted">
                      {nameOf(transfer.from)} &rarr; {nameOf(transfer.to)}
                    </span>
                    <span className="tabular font-mono text-ink">
                      {formatPaise(transfer.amountPaise)}
                    </span>
                  </li>
                ))}
              </ul>
              <p className="mt-2 text-xs text-ink-dim">
                {/* Said plainly, because a plan that suggests paying someone you never
                    shared an expense with looks like a bug until you know why. */}
                The fewest payments that settle everyone &mdash; which may not match who paid for
                what.
              </p>
            </>
          )}
        </div>
      )}
    </section>
  )
}
