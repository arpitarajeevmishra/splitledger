import { useMemo, useState } from 'react'
import { messageFor } from '../../api/problem'
import type { GroupDetail, PaymentMethod, SuggestedTransfer } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Field } from '../../components/ui/Field'
import { formatPaise, safeParsePaise } from '../../lib/money'
import { PAYMENT_METHODS } from './methods'
import { useCreateSettlement, useStartOrder } from './useSettlements'

/**
 * Record a payment towards what you owe.
 *
 * <p>Pre-filled from the simplified plan when there is a suggestion for you, because the
 * plan is the answer to "what should I actually pay" and retyping it is how people pay
 * the wrong person.
 */
export function SettleUpDialog({
  group,
  youId,
  suggestion,
  open,
  onClose,
}: {
  group: GroupDetail
  youId: string | undefined
  suggestion: SuggestedTransfer | undefined
  open: boolean
  onClose: () => void
}) {
  const create = useCreateSettlement(group.id)
  const startOrder = useStartOrder(group.id)

  const others = useMemo(
    () => group.members.filter((member) => member.userId !== youId),
    [group.members, youId],
  )

  const [toUser, setToUser] = useState(suggestion?.to ?? others[0]?.userId ?? '')
  const [amount, setAmount] = useState(
    suggestion === undefined ? '' : formatPaise(suggestion.amountPaise, { symbol: false }),
  )
  const [method, setMethod] = useState<PaymentMethod>('CASH')
  const [formError, setFormError] = useState<string | null>(null)

  const parsed = safeParsePaise(amount)
  const amountPaise = 'paise' in parsed ? parsed.paise : 0
  const amountError = amount === '' ? null : 'error' in parsed ? parsed.error : null
  const chosen = PAYMENT_METHODS.find((candidate) => candidate.method === method)
  const submittable = toUser !== '' && amountPaise > 0 && chosen?.unavailable == null

  function close() {
    setFormError(null)
    onClose()
  }

  async function submit() {
    setFormError(null)
    try {
      const settlement = await create.mutateAsync({
        settlement: { toUser, amountPaise, method },
        idempotencyKey: crypto.randomUUID(),
      })
      // A gateway method needs an order before anything can confirm it; cash and UPI are
      // settled by people, not providers.
      if (method === 'SANDBOX' || method === 'RAZORPAY') {
        await startOrder.mutateAsync(settlement.id)
      }
      close()
    } catch (error) {
      setFormError(messageFor(error, 'Could not record that payment.'))
    }
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title="Settle up"
      description="Records a payment. Nobody is charged — every figure here is made up."
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <label
            htmlFor="settle-to"
            className="text-[13px] font-medium tracking-[0.01em] text-ink-muted"
          >
            Pay
          </label>
          <select
            id="settle-to"
            value={toUser}
            onChange={(event) => setToUser(event.target.value)}
            className="h-10 rounded border border-line bg-surface-1 px-3 font-mono text-sm text-ink focus:border-info focus:outline-none"
          >
            {others.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.name}
              </option>
            ))}
          </select>
        </div>

        <Field
          label="Amount"
          name="settle-amount"
          placeholder="300.00"
          inputMode="decimal"
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          error={amountError ?? undefined}
        />

        <fieldset className="flex flex-col gap-2">
          <legend className="text-[13px] font-medium tracking-[0.01em] text-ink-muted">How</legend>
          <div className="flex flex-col gap-2">
            {PAYMENT_METHODS.map((option) => {
              const disabled = option.unavailable !== null
              return (
                <label
                  key={option.method}
                  className={`flex items-start gap-3 rounded border px-3 py-2 ${
                    disabled
                      ? 'cursor-not-allowed border-line opacity-60'
                      : method === option.method
                        ? 'cursor-pointer border-accent'
                        : 'cursor-pointer border-line hover:border-line-strong'
                  }`}
                >
                  <input
                    type="radio"
                    name="settle-method"
                    value={option.method}
                    checked={method === option.method}
                    disabled={disabled}
                    onChange={() => setMethod(option.method)}
                    className="mt-1 accent-[var(--accent)]"
                  />
                  <span className="min-w-0">
                    <span className="block text-sm text-ink">{option.label}</span>
                    <span className="mt-0.5 block text-xs text-ink-dim">{option.blurb}</span>
                    {disabled && (
                      <span className="mt-1 block text-xs text-negative">{option.unavailable}</span>
                    )}
                  </span>
                </label>
              )
            })}
          </div>
        </fieldset>

        <p className="rounded border border-line bg-surface-2 px-3 py-2 text-xs text-ink-dim">
          {/* On every method, not only the ones that look like payments. Cash included,
              because "this is a demo" is the one thing nobody should have to infer. */}
          No real money moves. This is a demo and the balances are invented.
        </p>

        {formError !== null && (
          <p
            role="alert"
            className="rounded border border-negative/40 bg-negative/10 px-3 py-2 text-xs text-negative"
          >
            {formError}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => void submit()}
            disabled={!submittable || create.isPending || startOrder.isPending}
          >
            {create.isPending || startOrder.isPending ? 'Recording…' : 'Record payment'}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
