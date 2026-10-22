import type { PaymentMethod } from '../../api/types'

/**
 * What each payment method is, and what it is not.
 *
 * <p>`movesRealMoney` is false for every method here, and the UI says so on each one. That
 * is not decoration: every figure in this product is invented, and a screen that looks
 * like a payment screen without saying it is a demo invites someone to believe a number
 * that was never real (ADR-013).
 */
export type MethodInfo = {
  method: PaymentMethod
  label: string
  blurb: string
  /** False everywhere in this product. Kept explicit so the labelling is data, not prose. */
  movesRealMoney: boolean
  /** Why this cannot be chosen here, or null when it can. */
  unavailable: string | null
}

export const PAYMENT_METHODS: MethodInfo[] = [
  {
    method: 'CASH',
    label: 'Cash',
    blurb: 'You hand it over in person. Nothing is charged anywhere.',
    movesRealMoney: false,
    unavailable: null,
  },
  {
    method: 'SANDBOX',
    label: 'Demo payment',
    blurb: 'Stands in for a card payment and confirms itself after a few seconds.',
    movesRealMoney: false,
    unavailable: null,
  },
  {
    method: 'UPI_INTENT',
    label: 'UPI',
    blurb: 'Opens your UPI app with the amount filled in.',
    movesRealMoney: false,
    // The one method that could reach a real bank account, so it is switched off in this
    // deployment. Shown rather than hidden: a missing option raises more questions than a
    // disabled one that explains itself.
    unavailable: 'Switched off here — this demo never moves real money.',
  },
  {
    method: 'RAZORPAY',
    label: 'Card',
    blurb: 'Razorpay test mode.',
    movesRealMoney: false,
    unavailable: 'Arrives in a later sprint.',
  },
]

export function methodInfo(method: PaymentMethod): MethodInfo {
  return (
    PAYMENT_METHODS.find((candidate) => candidate.method === method) ?? {
      method,
      label: method,
      blurb: '',
      movesRealMoney: false,
      unavailable: null,
    }
  )
}
