/**
 * Razorpay Checkout, loaded on demand (SL-605).
 *
 * The script is fetched the first time someone chooses to pay by card, not on every page
 * load: it is ~190 KB of a third party's JavaScript, and most visitors of a demo will
 * never open it. Loading it eagerly would also mean every page of this application making
 * a request to a payment gateway, which is not a thing a balance screen should do.
 */

const SCRIPT_URL = 'https://checkout.razorpay.com/v1/checkout.js'

/** What Checkout hands back when a payment succeeds. Their field names, verbatim. */
export type CheckoutSuccess = {
  razorpay_order_id: string
  razorpay_payment_id: string
  razorpay_signature: string
}

type RazorpayOptions = {
  key: string
  order_id: string
  amount: number
  currency: string
  name: string
  description: string
  handler: (response: CheckoutSuccess) => void
  modal: { ondismiss: () => void }
  theme?: { color?: string }
}

type RazorpayInstance = { open: () => void }

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => RazorpayInstance
  }
}

let loading: Promise<void> | null = null

/**
 * Put the script in the page once.
 *
 * The promise is cached rather than the boolean, so two people clicking Pay at the same
 * moment wait on one network request instead of appending two script tags. A failure
 * clears the cache, so a retry is a real retry and not a replay of the rejection.
 */
export function loadCheckout(): Promise<void> {
  if (window.Razorpay !== undefined) {
    return Promise.resolve()
  }
  if (loading !== null) {
    return loading
  }

  loading = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      // Blocked by a content-security policy, an ad blocker, or an offline gateway. The
      // caller turns this into a message; what it must not do is leave the payer looking
      // at a button that does nothing.
      reject(new Error('Could not load the payment gateway'))
    }
    document.head.appendChild(script)
  }).catch((failure: unknown) => {
    loading = null
    throw failure
  })

  return loading
}

/** Visible for testing: forget the cached load so each test starts clean. */
export function resetCheckoutForTests(): void {
  loading = null
  delete window.Razorpay
}

/**
 * Open Checkout for an order, and resolve with what it reports.
 *
 * Resolves null when the payer closes the modal without paying. That is not an error:
 * abandoning a checkout is a normal thing to do, the settlement stays pending, and its own
 * deadline will reclaim it (SL-604).
 */
export async function openCheckout(options: {
  keyId: string
  orderId: string
  amountPaise: number
  groupName: string
}): Promise<CheckoutSuccess | null> {
  await loadCheckout()

  const Razorpay = window.Razorpay
  if (Razorpay === undefined) {
    throw new Error('Could not load the payment gateway')
  }

  return new Promise<CheckoutSuccess | null>((resolve) => {
    let settled = false
    const once = (value: CheckoutSuccess | null) => {
      // Checkout can fire the handler and then the dismiss callback. Resolving twice is
      // harmless for a promise but the guard keeps the intent obvious.
      if (!settled) {
        settled = true
        resolve(value)
      }
    }

    new Razorpay({
      key: options.keyId,
      order_id: options.orderId,
      // Paise, which is Razorpay's unit and ours (ADR-002). The order already fixes the
      // amount server-side; this is what their modal displays.
      amount: options.amountPaise,
      currency: 'INR',
      name: 'SplitLedger',
      description: `Settling up in ${options.groupName}`,
      handler: (response) => once(response),
      modal: { ondismiss: () => once(null) },
    }).open()
  })
}
