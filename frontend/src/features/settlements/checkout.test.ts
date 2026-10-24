import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadCheckout, openCheckout, resetCheckoutForTests } from './checkout'

/**
 * The gateway script loader (SL-605).
 *
 * Razorpay's script is a third party's 190 KB of JavaScript, so what matters here is that
 * it is fetched once, that a failure is a failure the caller can report, and that a
 * dismissed modal is not mistaken for a payment.
 */
describe('checkout', () => {
  let appended: HTMLScriptElement[] = []
  let appendSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    resetCheckoutForTests()
    appended = []
    appendSpy = vi.spyOn(document.head, 'appendChild').mockImplementation(((node: Node) => {
      appended.push(node as HTMLScriptElement)
      return node
    }) as typeof document.head.appendChild)
  })

  afterEach(() => {
    appendSpy.mockRestore()
    resetCheckoutForTests()
  })

  it('fetches the script once however many callers ask at the same moment', async () => {
    const first = loadCheckout()
    const second = loadCheckout()

    expect(appended).toHaveLength(1)
    appended[0]?.onload?.(new Event('load'))
    await Promise.all([first, second])

    // Two clicks must not append two script tags: the promise is cached, not the boolean.
    expect(appended).toHaveLength(1)
  })

  it('does not fetch it again once it is there', async () => {
    window.Razorpay = vi.fn() as unknown as NonNullable<typeof window.Razorpay>
    await loadCheckout()

    expect(appended).toHaveLength(0)
  })

  it('reports a load failure, and a retry is a real retry', async () => {
    const first = loadCheckout()
    appended[0]?.onerror?.(new Event('error'))
    await expect(first).rejects.toThrow(/could not load the payment gateway/i)

    // The cached rejection is cleared, so the next caller gets a fresh attempt rather
    // than a replay of the failure -- a blocked script and a flaky network look the same
    // from here, and one of them is worth trying again.
    const second = loadCheckout()
    expect(appended).toHaveLength(2)
    appended[1]?.onload?.(new Event('load'))
    await expect(second).resolves.toBeUndefined()
  })

  describe('openCheckout', () => {
    type Options = {
      key: string
      amount: number
      order_id: string
      handler: (r: unknown) => void
      modal: { ondismiss: () => void }
    }
    let captured: Options | null = null

    beforeEach(() => {
      captured = null
      // A function declaration, not an arrow: the real Razorpay is called with `new`,
      // and an arrow function is not a constructor.
      window.Razorpay = vi.fn().mockImplementation(function (options: Options) {
        captured = options
        return { open: vi.fn() }
      }) as unknown as NonNullable<typeof window.Razorpay>
    })

    it('opens the order in paise, with the public key', async () => {
      const pending = openCheckout({
        keyId: 'rzp_test_public',
        orderId: 'order_1',
        amountPaise: 45_000,
        groupName: 'Flat 402',
      })
      await vi.waitFor(() => expect(captured).not.toBeNull())

      expect(captured?.key).toBe('rzp_test_public')
      expect(captured?.order_id).toBe('order_1')
      // Paise, which is Razorpay's unit and ours. Rupees here would show a hundredth of
      // the amount in their modal.
      expect(captured?.amount).toBe(45_000)

      captured?.handler({ razorpay_payment_id: 'pay_1' })
      await expect(pending).resolves.toEqual({ razorpay_payment_id: 'pay_1' })
    })

    it('resolves null when the payer dismisses the modal', async () => {
      const pending = openCheckout({
        keyId: 'k',
        orderId: 'order_1',
        amountPaise: 45_000,
        groupName: 'Flat 402',
      })
      await vi.waitFor(() => expect(captured).not.toBeNull())

      captured?.modal.ondismiss()

      // Not an error: abandoning a checkout is a normal thing to do, and the caller must
      // be able to tell it apart from a payment.
      await expect(pending).resolves.toBeNull()
    })

    it('keeps the first answer when the gateway reports both', async () => {
      // Checkout can call the handler and then ondismiss as the modal closes. The payment
      // is what happened; the close is bookkeeping.
      const pending = openCheckout({
        keyId: 'k',
        orderId: 'order_1',
        amountPaise: 45_000,
        groupName: 'Flat 402',
      })
      await vi.waitFor(() => expect(captured).not.toBeNull())

      captured?.handler({ razorpay_payment_id: 'pay_1' })
      captured?.modal.ondismiss()

      await expect(pending).resolves.toEqual({ razorpay_payment_id: 'pay_1' })
    })
  })
})
