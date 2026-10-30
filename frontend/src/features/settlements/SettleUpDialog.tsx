import { useMemo, useState } from 'react'
import { useAppConfig } from '../../api/config'
import { messageFor } from '../../api/problem'
import type { GroupDetail, PaymentMethod, SuggestedTransfer } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Field } from '../../components/ui/Field'
import { formatPaise, safeParsePaise } from '../../lib/money'
import { openCheckout } from './checkout'
import { PAYMENT_METHODS } from './methods'
import { TestInstruments } from './TestInstruments'
import { useCreateSettlement, useGatewayCallback, useStartOrder } from './useSettlements'

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
  const gatewayCallback = useGatewayCallback(group.id)
  const { upiIntentEnabled: upiEnabled, razorpayKeyId } = useAppConfig()

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

  // Only methods this deployment would actually accept. UPI is absent rather than
  // disabled when the server has it off: it is the one method that could reach a real
  // bank account against balances that are fiction (ADR-013, risk R9), and an option
  // nobody can choose is still an option somebody can be confused by.
  const available = useMemo(
    () =>
      PAYMENT_METHODS.filter((option) => {
        if (option.method === 'UPI_INTENT') {
          return upiEnabled
        }
        // No key, no card payment. The provider is not even a bean without one, so an
        // order would come back a 502 -- offering a payment the server cannot take is
        // worse than not offering it (SL-605).
        if (option.method === 'RAZORPAY') {
          return razorpayKeyId !== null
        }
        return true
      }),
    [upiEnabled, razorpayKeyId],
  )

  const parsed = safeParsePaise(amount)
  const amountPaise = 'paise' in parsed ? parsed.paise : 0
  const amountError = amount === '' ? null : 'error' in parsed ? parsed.error : null
  const chosen = available.find((candidate) => candidate.method === method)
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
        const ordered = await startOrder.mutateAsync(settlement.id)

        if (method === 'RAZORPAY') {
          await payByCard(ordered.gatewayOrderId, settlement.id)
          return
        }
      }
      close()
    } catch (error) {
      setFormError(messageFor(error, 'Could not record that payment.'))
    }
  }

  /**
   * Open Checkout, and hand what it says back to the server.
   *
   * The dialog stays open until this resolves, because closing it would leave the payer
   * looking at a balance that has not moved yet with nothing to explain why.
   *
   * Nothing here is trusted: the three strings Checkout hands over are posted to the
   * server, which verifies the signature and matches the order against the one it stored
   * before it will confirm anything (SL-602). The browser's word is a claim, not proof.
   */
  async function payByCard(orderId: string | null, settlementId: string) {
    if (razorpayKeyId === null || orderId === null) {
      // Neither is reachable through the UI -- the method is filtered out without a key,
      // and an order was just created. Checked because the alternative is opening a
      // gateway modal with an undefined order id.
      setFormError('Card payments are not configured here.')
      return
    }

    const answer = await openCheckout({
      keyId: razorpayKeyId,
      orderId,
      amountPaise,
      groupName: group.name,
    })

    if (answer === null) {
      // Dismissed without paying. The settlement stays pending and its deadline will
      // reclaim it (SL-604), so there is nothing to undo and nothing to apologise for.
      close()
      return
    }

    await gatewayCallback.mutateAsync({
      settlementId,
      callback: {
        orderId: answer.razorpay_order_id,
        paymentId: answer.razorpay_payment_id,
        signature: answer.razorpay_signature,
      },
    })
    close()
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title="Settle up"
      description="Records a payment. Nobody is charged — every figure here is made up."
    >
      {/* D3: a badge on the payment surface itself. The page banner says the product is
          a demo; this says it about the screen someone is about to pay on. */}
      <p className="mb-4 inline-block rounded border border-info/40 bg-info/10 px-2 py-1 font-mono text-[10px] tracking-[0.06em] text-info uppercase">
        Test mode
      </p>
      <div className="flex flex-col gap-4">
        {method === 'RAZORPAY' && <TestInstruments />}
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
            className="h-10 rounded border border-line bg-surface-1 px-3 font-mono text-sm text-ink focus:border-info"
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
            {available.map((option) => {
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
