import { useState } from 'react'
import { messageFor } from '../../api/problem'
import type { GroupDetail, Settlement, SettlementStatus } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { formatPaise } from '../../lib/money'
import { methodInfo } from './methods'
import {
  useCancelSettlement,
  useConfirmSettlement,
  useMarkPaid,
  useRejectSettlement,
  useSettlements,
} from './useSettlements'

const STATUS_STYLE: Record<SettlementStatus, string> = {
  INITIATED: 'text-ink-muted',
  PENDING: 'text-info',
  CONFIRMED: 'text-positive',
  FAILED: 'text-negative',
  CANCELLED: 'text-ink-dim',
  EXPIRED: 'text-ink-dim',
}

/**
 * D5: a confirmed payment says no funds moved, not "payment successful".
 *
 * <p>"Settled" is true of the ledger and says nothing about the bank. Someone reading it
 * on a screen that looks like a payment screen can reasonably conclude money left their
 * account, and the one thing this product must never do is let somebody believe that.
 */
const STATUS_LABEL: Record<SettlementStatus, string> = {
  INITIATED: 'not sent yet',
  PENDING: 'waiting to be confirmed',
  CONFIRMED: 'simulated payment — no funds were transferred',
  FAILED: 'rejected',
  CANCELLED: 'cancelled',
  EXPIRED: 'expired',
}

export function SettlementsPanel({
  group,
  youId,
}: {
  group: GroupDetail
  youId: string | undefined
}) {
  const { data: settlements, isPending, isError, error } = useSettlements(group.id)
  const markPaid = useMarkPaid(group.id)
  const confirm = useConfirmSettlement(group.id)
  const reject = useRejectSettlement(group.id)
  const cancel = useCancelSettlement(group.id)
  const [actionError, setActionError] = useState<string | null>(null)

  const nameOf = (userId: string) =>
    group.members.find((member) => member.userId === userId)?.name ?? 'Someone'

  const busy = markPaid.isPending || confirm.isPending || reject.isPending || cancel.isPending

  async function run(action: { mutateAsync: (id: string) => Promise<unknown> }, id: string) {
    setActionError(null)
    try {
      await action.mutateAsync(id)
    } catch (failure) {
      // A debt that moved under a pending settlement lands here, and the message is the
      // useful part -- it says the amount is now more than is outstanding.
      setActionError(messageFor(failure, 'Could not do that.'))
    }
  }

  /** What this person may do to this settlement, which is not what they may see. */
  function actionsFor(settlement: Settlement) {
    const youArePayer = settlement.fromUser === youId
    const youArePayee = settlement.toUser === youId
    const actions: { label: string; run: () => void }[] = []

    if (youArePayer && settlement.status === 'INITIATED') {
      actions.push({ label: 'I have paid', run: () => void run(markPaid, settlement.id) })
      actions.push({ label: 'Cancel', run: () => void run(cancel, settlement.id) })
    }
    if (youArePayer && settlement.status === 'PENDING' && settlement.method === 'CASH') {
      actions.push({ label: 'Cancel', run: () => void run(cancel, settlement.id) })
    }
    if (youArePayee && settlement.status === 'PENDING') {
      // Only the receiver confirms. The payer declaring their own payment settled would
      // let anyone clear a debt by saying so.
      actions.push({ label: 'Confirm', run: () => void run(confirm, settlement.id) })
      actions.push({ label: 'Reject', run: () => void run(reject, settlement.id) })
    }
    return actions
  }

  return (
    <section className="rounded-lg border border-line bg-surface-1">
      <div className="border-b border-line px-4 py-3">
        <h2 className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
          Payments
        </h2>
      </div>

      {isPending && <p className="px-4 py-6 text-center text-sm text-ink-dim">Loading payments…</p>}

      {isError && (
        <p role="alert" className="px-4 py-6 text-center text-sm text-negative">
          {messageFor(error, 'Could not load payments.')}
        </p>
      )}

      {actionError !== null && (
        <p role="alert" className="px-4 pt-4 text-xs text-negative">
          {actionError}
        </p>
      )}

      {settlements?.length === 0 && (
        <div className="px-4 py-6 text-center">
          <p className="text-sm text-ink-muted">No payments yet.</p>
          <p className="mt-1 text-xs text-ink-dim">
            Settle up from the balances panel when someone owes something.
          </p>
        </div>
      )}

      {settlements !== undefined && settlements.length > 0 && (
        <ul>
          {settlements.map((settlement) => (
            <li
              key={settlement.id}
              className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-3 last:border-b-0"
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm text-ink">
                  {nameOf(settlement.fromUser)} &rarr; {nameOf(settlement.toUser)}
                </span>
                <span className="mt-0.5 block font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
                  {methodInfo(settlement.method).label}
                  {' · '}
                  <span className={STATUS_STYLE[settlement.status]}>
                    {STATUS_LABEL[settlement.status]}
                  </span>
                </span>
              </span>
              <span className="tabular font-mono text-sm text-ink">
                {formatPaise(settlement.amountPaise)}
              </span>
              {actionsFor(settlement).map((action) => (
                <Button key={action.label} variant="ghost" disabled={busy} onClick={action.run}>
                  {action.label}
                </Button>
              ))}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
