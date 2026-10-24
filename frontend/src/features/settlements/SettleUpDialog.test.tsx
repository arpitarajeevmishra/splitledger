import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupDetail } from '../../api/types'
import { renderWithProviders, SOMEONE } from '../../test/renderWithProviders'
import { PAYMENT_METHODS } from './methods'
import { SettleUpDialog } from './SettleUpDialog'

const { createSettlement, startOrder, gatewayCallback } = vi.hoisted(() => ({
  createSettlement: vi.fn(),
  startOrder: vi.fn(),
  gatewayCallback: vi.fn(),
}))
vi.mock('./useSettlements', () => ({
  useCreateSettlement: () => ({ mutateAsync: createSettlement, isPending: false }),
  useStartOrder: () => ({ mutateAsync: startOrder, isPending: false }),
  useGatewayCallback: () => ({ mutateAsync: gatewayCallback, isPending: false }),
}))

const { openCheckout } = vi.hoisted(() => ({ openCheckout: vi.fn() }))
vi.mock('./checkout', () => ({ openCheckout }))

const { useAppConfig } = vi.hoisted(() => ({ useAppConfig: vi.fn() }))
vi.mock('../../api/config', () => ({ useAppConfig }))

const BOB = '22222222-2222-2222-2222-222222222222'

const GROUP: GroupDetail = {
  id: 'g1',
  name: 'Flat 402',
  currency: 'INR',
  yourRole: 'ADMIN',
  createdAt: '2026-01-01T10:00:00Z',
  members: [
    { userId: SOMEONE.id, name: 'Alice Ray', role: 'ADMIN', joinedAt: '2026-01-01T10:00:00Z' },
    { userId: BOB, name: 'Bob Stone', role: 'MEMBER', joinedAt: '2026-01-02T10:00:00Z' },
  ],
}

function renderDialog(suggestion?: { from: string; to: string; amountPaise: number }) {
  renderWithProviders(
    <SettleUpDialog
      group={GROUP}
      youId={SOMEONE.id}
      suggestion={suggestion}
      open
      onClose={vi.fn()}
    />,
  )
}

describe('SettleUpDialog', () => {
  beforeEach(() => {
    createSettlement.mockReset()
    createSettlement.mockResolvedValue({ id: 's1' })
    startOrder.mockReset()
    startOrder.mockResolvedValue({ id: 's1', gatewayOrderId: 'order_test_1' })
    gatewayCallback.mockReset()
    gatewayCallback.mockResolvedValue({ id: 's1', status: 'CONFIRMED' })
    openCheckout.mockReset()
    // The safe default, which is also what the hook falls back to while loading.
    useAppConfig.mockReturnValue({
      registrationEnabled: false,
      upiIntentEnabled: false,
      demoMode: true,
      razorpayKeyId: null,
    })
  })

  it('says no real money moves, whatever is selected', () => {
    renderDialog()

    // The acceptance criterion, and it is not conditional on the method -- cash included,
    // because "this is a demo" is the one thing nobody should have to infer.
    expect(screen.getByText(/No real money moves/i)).toBeInTheDocument()
  })

  it('offers no method that claims to move real money', () => {
    // A guard on the data rather than the rendering: if someone ever adds a live method,
    // this fails before the labelling silently becomes untrue.
    expect(PAYMENT_METHODS.every((option) => !option.movesRealMoney)).toBe(true)
  })

  it('does not offer UPI when the server has it switched off', () => {
    // Absent, not disabled. It is the one method that could reach a real bank account
    // against balances that are fiction (ADR-013, risk R9), and an option nobody can
    // choose is still an option somebody can be confused by.
    renderDialog()

    expect(screen.queryByRole('radio', { name: /UPI/ })).not.toBeInTheDocument()
  })

  it('offers UPI when the server says it is enabled', () => {
    useAppConfig.mockReturnValue({
      registrationEnabled: false,
      upiIntentEnabled: true,
      demoMode: true,
    })
    renderDialog()

    // Read from the server rather than compiled in: a method the backend would accept is
    // never hidden, and one it would refuse is never offered.
    expect(screen.getByRole('radio', { name: /UPI/ })).toBeInTheDocument()
  })

  it('badges the payment surface as test mode', () => {
    renderDialog()

    // The page banner says the product is a demo; this says it about the screen someone
    // is about to pay on (D3).
    expect(screen.getByText('Test mode')).toBeInTheDocument()
  })

  it('pre-fills from the simplified plan', () => {
    renderDialog({ from: SOMEONE.id, to: BOB, amountPaise: 30_000 })

    // Retyping the suggestion is how people pay the wrong person or the wrong amount.
    expect(screen.getByLabelText('Amount')).toHaveValue('300.00')
    expect(screen.getByLabelText('Pay')).toHaveValue(BOB)
  })

  it('records a cash payment without starting an order', async () => {
    renderDialog({ from: SOMEONE.id, to: BOB, amountPaise: 30_000 })

    await userEvent.click(screen.getByRole('button', { name: 'Record payment' }))

    expect(createSettlement).toHaveBeenCalledTimes(1)
    const sent = createSettlement.mock.calls[0]?.[0]
    expect(sent.settlement).toEqual({ toUser: BOB, amountPaise: 30_000, method: 'CASH' })
    expect(sent.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
    // Cash is settled by people, not providers.
    expect(startOrder).not.toHaveBeenCalled()
  })

  it('starts an order for the demo payment method', async () => {
    renderDialog({ from: SOMEONE.id, to: BOB, amountPaise: 30_000 })
    await userEvent.click(screen.getByRole('radio', { name: /Demo payment/ }))

    await userEvent.click(screen.getByRole('button', { name: 'Record payment' }))

    expect(createSettlement.mock.calls[0]?.[0].settlement.method).toBe('SANDBOX')
    expect(startOrder).toHaveBeenCalledWith('s1')
  })

  it('cannot be submitted without an amount', async () => {
    renderDialog()

    expect(screen.getByRole('button', { name: 'Record payment' })).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Amount'), '300')
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeEnabled()
  })

  it('refuses an amount finer than a paisa', async () => {
    renderDialog()
    await userEvent.type(screen.getByLabelText('Amount'), '1.005')

    expect(await screen.findByText(/smaller than a paisa/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeDisabled()
  })

  it('does not offer to pay yourself', () => {
    renderDialog()

    const options = screen.getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual(['Bob Stone'])
  })

  describe('paying by card (SL-605)', () => {
    const WITH_KEYS = {
      registrationEnabled: false,
      upiIntentEnabled: false,
      demoMode: true,
      razorpayKeyId: 'rzp_test_public',
    }

    it('does not offer a card payment when the deployment has no keys', () => {
      // Without a key the provider is not even created, so an order would be a 502.
      // Offering a payment the server cannot take is worse than not offering it.
      renderDialog()

      expect(screen.queryByRole('radio', { name: /Card/ })).not.toBeInTheDocument()
    })

    it('offers it once the server reports a key', () => {
      useAppConfig.mockReturnValue(WITH_KEYS)
      renderDialog()

      expect(screen.getByRole('radio', { name: /Card/ })).toBeInTheDocument()
    })

    it("shows Razorpay's test cards only once card is the chosen method", async () => {
      // D4. Nobody memorises test card numbers, and a visitor who reaches the gateway
      // with nothing to type leaves.
      useAppConfig.mockReturnValue(WITH_KEYS)
      renderDialog()

      expect(screen.queryByText('4100 2800 0000 1007')).not.toBeInTheDocument()

      await userEvent.click(screen.getByRole('radio', { name: /Card/ }))

      expect(screen.getByText('4100 2800 0000 1007')).toBeInTheDocument()
      expect(screen.getByText(/any future date, any 3 digits/)).toBeInTheDocument()
    })

    it('opens checkout for the order it just created and posts what comes back', async () => {
      useAppConfig.mockReturnValue(WITH_KEYS)
      openCheckout.mockResolvedValue({
        razorpay_order_id: 'order_test_1',
        razorpay_payment_id: 'pay_test_1',
        razorpay_signature: 'a-signature',
      })
      renderDialog({ from: SOMEONE.id, to: BOB, amountPaise: 45_000 })

      await userEvent.click(screen.getByRole('radio', { name: /Card/ }))
      await userEvent.click(screen.getByRole('button', { name: 'Record payment' }))

      // The order id comes from the server's response, not from anything the client
      // invented: it is the only thing a later callback can be matched on.
      expect(openCheckout).toHaveBeenCalledWith(
        expect.objectContaining({ keyId: 'rzp_test_public', orderId: 'order_test_1' }),
      )
      // And the three strings go straight to the server, which verifies the signature
      // before it believes any of them.
      expect(gatewayCallback).toHaveBeenCalledWith({
        settlementId: 's1',
        callback: {
          orderId: 'order_test_1',
          paymentId: 'pay_test_1',
          signature: 'a-signature',
        },
      })
    })

    it('posts nothing when the payer closes the gateway without paying', async () => {
      // Abandoning a checkout is normal. The settlement stays pending and its deadline
      // reclaims it (SL-604); posting a callback here would be inventing a payment.
      useAppConfig.mockReturnValue(WITH_KEYS)
      openCheckout.mockResolvedValue(null)
      renderDialog({ from: SOMEONE.id, to: BOB, amountPaise: 45_000 })

      await userEvent.click(screen.getByRole('radio', { name: /Card/ }))
      await userEvent.click(screen.getByRole('button', { name: 'Record payment' }))

      expect(openCheckout).toHaveBeenCalled()
      expect(gatewayCallback).not.toHaveBeenCalled()
    })

    it('says so when the gateway script cannot be loaded', async () => {
      // Blocked by a content-security policy, an ad blocker, or an offline gateway. What
      // the payer must not get is a button that silently does nothing.
      useAppConfig.mockReturnValue(WITH_KEYS)
      openCheckout.mockRejectedValue(new Error('Could not load the payment gateway'))
      renderDialog({ from: SOMEONE.id, to: BOB, amountPaise: 45_000 })

      await userEvent.click(screen.getByRole('radio', { name: /Card/ }))
      await userEvent.click(screen.getByRole('button', { name: 'Record payment' }))

      expect(await screen.findByText(/could not record that payment/i)).toBeInTheDocument()
      expect(gatewayCallback).not.toHaveBeenCalled()
    })
  })
})
