import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupDetail } from '../../api/types'
import { renderWithProviders, SOMEONE } from '../../test/renderWithProviders'
import { PAYMENT_METHODS } from './methods'
import { SettleUpDialog } from './SettleUpDialog'

const { createSettlement, startOrder } = vi.hoisted(() => ({
  createSettlement: vi.fn(),
  startOrder: vi.fn(),
}))
vi.mock('./useSettlements', () => ({
  useCreateSettlement: () => ({ mutateAsync: createSettlement, isPending: false }),
  useStartOrder: () => ({ mutateAsync: startOrder, isPending: false }),
}))

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
    startOrder.mockResolvedValue({ id: 's1' })
    // The safe default, which is also what the hook falls back to while loading.
    useAppConfig.mockReturnValue({
      registrationEnabled: false,
      upiIntentEnabled: false,
      demoMode: true,
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
})
