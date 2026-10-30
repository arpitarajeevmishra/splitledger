import { useState } from 'react'
import { messageFor } from '../../api/problem'
import type { GroupDetail } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { formatPaise } from '../../lib/money'
import { SettleUpDialog } from '../settlements/SettleUpDialog'
import { useSettlements } from '../settlements/useSettlements'
import { useBalances, useSimplifiedPlan } from './useExpenses'

export function BalancesPanel({ group, youId }: { group: GroupDetail; youId: string | undefined }) {
  const { data: balances, isPending, isError, error } = useBalances(group.id)
  const { data: plan } = useSimplifiedPlan(group.id)
  const [settling, setSettling] = useState(false)

  // The plan's suggestion for you, if it has one. Pre-filling it is the difference
  // between "what should I pay" being answered and being retyped.
  const yours = plan?.transfers.find((transfer) => transfer.from === youId)

  const { data: settlements } = useSettlements(group.id)

  /**
   * A payment this person started that has not finished yet.
   *
   * INITIATED and PENDING both count: the first is created and not yet sent, the second
   * is sent and waiting on the other person. Either way a second settlement for the same
   * debt can only end in one of them failing.
   */
  const awaitingPayment = settlements?.find(
    (settlement) =>
      settlement.fromUser === youId &&
      (settlement.status === 'INITIATED' || settlement.status === 'PENDING'),
  )

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
              className="flex items-center justify-between border-b border-line px-4 py-3 last:border-b-0"
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
              {yours !== undefined && (
                <>
                  <Button
                    className="mt-3"
                    onClick={() => setSettling(true)}
                    disabled={awaitingPayment !== undefined}
                  >
                    Settle up
                  </Button>
                  {awaitingPayment !== undefined && (
                    // Without this the button stays live while a payment is already in
                    // flight, and clicking it again makes a second settlement for the same
                    // debt. The first one confirms, the rest cannot -- which is what
                    // produced "you do not owe this group anything" for the person trying
                    // to confirm.
                    <p className="mt-2 text-[12px] text-ink-muted">
                      You already have a payment waiting to be confirmed. Settle up again once it is
                      finished or cancelled.
                    </p>
                  )}
                </>
              )}
            </>
          )}
        </div>
      )}

      {/* Mounted only while open, so its fields initialise from the plan as it stands
          now. Keeping it mounted meant useState ran once on first render -- before the
          plan had loaded -- and the suggested amount was captured as undefined and never
          picked up again. It also means the form starts clean each time. */}
      {settling && (
        <SettleUpDialog
          group={group}
          youId={youId}
          suggestion={yours}
          open
          onClose={() => setSettling(false)}
        />
      )}
    </section>
  )
}
