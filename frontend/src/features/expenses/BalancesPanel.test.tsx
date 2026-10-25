import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Balances, GroupDetail, SimplifiedPlan } from '../../api/types'
import { renderWithProviders, SOMEONE } from '../../test/renderWithProviders'
import { BalancesPanel } from './BalancesPanel'

const { useBalances, useSimplifiedPlan } = vi.hoisted(() => ({
  useBalances: vi.fn(),
  useSimplifiedPlan: vi.fn(),
}))
vi.mock('./useExpenses', () => ({ useBalances, useSimplifiedPlan }))
const { useSettlements } = vi.hoisted(() => ({ useSettlements: vi.fn() }))
vi.mock('../settlements/useSettlements', () => ({
  useSettlements,
  useCreateSettlement: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useStartOrder: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useGatewayCallback: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

const BOB = '22222222-2222-2222-2222-222222222222'

const GROUP: GroupDetail = {
  id: 'g1',
  name: 'Flat 402',
  currency: 'INR',
  yourRole: 'MEMBER',
  createdAt: '2026-01-01T10:00:00Z',
  members: [
    { userId: SOMEONE.id, name: 'Alice Ray', role: 'MEMBER', joinedAt: '2026-01-01T10:00:00Z' },
    { userId: BOB, name: 'Bob Stone', role: 'ADMIN', joinedAt: '2026-01-02T10:00:00Z' },
  ],
}

const BALANCES: Balances = {
  net: [
    { userId: SOMEONE.id, netPaise: -30_000, settled: false },
    { userId: BOB, netPaise: 30_000, settled: false },
  ],
  pairwise: [{ from: SOMEONE.id, to: BOB, amountPaise: 30_000 }],
}

const PLAN: SimplifiedPlan = {
  transfers: [{ from: SOMEONE.id, to: BOB, amountPaise: 30_000 }],
  settled: false,
}

describe('BalancesPanel', () => {
  beforeEach(() => {
    useBalances.mockReturnValue({ data: BALANCES, isPending: false, isError: false })
    useSimplifiedPlan.mockReturnValue({ data: PLAN })
    // No payment in flight is the ordinary case; the tests that care set their own.
    useSettlements.mockReturnValue({ data: [] })
  })

  it('shows each net position and the simplified plan', () => {
    renderWithProviders(<BalancesPanel group={GROUP} youId={SOMEONE.id} />)

    expect(screen.getByText('-₹300.00')).toBeInTheDocument()
    expect(screen.getByText(/may not match who paid for what/i)).toBeInTheDocument()
  })

  it('offers to settle only when the plan asks something of you', () => {
    renderWithProviders(<BalancesPanel group={GROUP} youId={BOB} />)

    // Bob is owed; the plan has nothing for him to pay.
    expect(screen.queryByRole('button', { name: 'Settle up' })).not.toBeInTheDocument()
  })

  it('pre-fills the dialog from the plan once it has loaded', async () => {
    // The plan arrives *after* the first render, which is the whole point.
    //
    // The dialog used to mount together with the panel, so its fields initialised while
    // the suggestion was still undefined and never picked it up -- the button stayed
    // disabled and nothing on screen explained why. A mock that hands over the plan
    // synchronously hides this entirely, which is why the first version of this test
    // passed against the bug it was written for.
    useSimplifiedPlan.mockReturnValueOnce({ data: undefined })
    const view = renderWithProviders(<BalancesPanel group={GROUP} youId={SOMEONE.id} />)

    // Nothing to settle yet: no plan, no suggestion, no button.
    expect(screen.queryByRole('button', { name: 'Settle up' })).not.toBeInTheDocument()

    // The query resolves and the panel re-renders, exactly as it does in the browser.
    view.renderAgain()

    await userEvent.click(screen.getByRole('button', { name: 'Settle up' }))

    expect(screen.getByLabelText('Amount')).toHaveValue('300.00')
    expect(screen.getByLabelText('Pay')).toHaveValue(BOB)
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeEnabled()
  })

  it('says nobody owes anybody when the group is settled', () => {
    useSimplifiedPlan.mockReturnValue({ data: { transfers: [], settled: true } })
    renderWithProviders(<BalancesPanel group={GROUP} youId={SOMEONE.id} />)

    expect(screen.getByText('Nobody owes anybody.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Settle up' })).not.toBeInTheDocument()
  })

  describe('while a payment is already in flight', () => {
    function inFlight(status: 'INITIATED' | 'PENDING') {
      useSettlements.mockReturnValue({
        data: [{ id: 's1', fromUser: SOMEONE.id, toUser: BOB, status, method: 'CASH' }],
      })
    }

    it.each(['INITIATED', 'PENDING'] as const)(
      'will not let you start another one (%s)',
      (status) => {
        // Clicking Settle up again made a second settlement for the same debt. The first
        // confirms and the rest cannot, which is what produced an error for the person
        // trying to confirm.
        inFlight(status)
        renderWithProviders(<BalancesPanel group={GROUP} youId={SOMEONE.id} />)

        expect(screen.getByRole('button', { name: 'Settle up' })).toBeDisabled()
        expect(screen.getByText(/already have a payment waiting/i)).toBeInTheDocument()
      },
    )

    it('is offered again once that payment is finished', () => {
      useSettlements.mockReturnValue({
        data: [
          { id: 's1', fromUser: SOMEONE.id, toUser: BOB, status: 'CONFIRMED', method: 'CASH' },
        ],
      })
      renderWithProviders(<BalancesPanel group={GROUP} youId={SOMEONE.id} />)

      expect(screen.getByRole('button', { name: 'Settle up' })).toBeEnabled()
    })

    it("is not blocked by somebody else's payment", () => {
      // Two people can owe the same person at once; only your own in-flight payment is a
      // reason to stop you starting one.
      useSettlements.mockReturnValue({
        data: [{ id: 's1', fromUser: BOB, toUser: SOMEONE.id, status: 'PENDING', method: 'CASH' }],
      })
      renderWithProviders(<BalancesPanel group={GROUP} youId={SOMEONE.id} />)

      expect(screen.getByRole('button', { name: 'Settle up' })).toBeEnabled()
    })
  })
})
