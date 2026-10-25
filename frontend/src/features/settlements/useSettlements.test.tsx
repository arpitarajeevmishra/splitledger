import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Settlement, SettlementStatus } from '../../api/types'
import { expenseKeys } from '../expenses/useExpenses'
import { useSettlements } from './useSettlements'

const { list } = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('../../api/settlements', () => ({ settlementsApi: () => ({ list }) }))

function row(status: SettlementStatus, version = 1): Settlement[] {
  return [
    {
      id: 's1',
      groupId: 'g1',
      fromUser: 'u1',
      toUser: 'u2',
      amountPaise: 10_000,
      method: 'CASH',
      status,
      gatewayOrderId: null,
      gatewayPaymentId: null,
      expiresAt: null,
      createdAt: '2026-10-25T10:00:00Z',
      confirmedAt: null,
      version,
      demoOnly: true,
    },
  ]
}

/**
 * The balances have to follow the settlements.
 *
 * A settlement confirmed by the *other* person moves the ledger, and this tab hears about
 * it only from the poll. Without the invalidation below, the payments list said "settled"
 * while the balances beside it still showed the debt and the plan still offered to settle
 * it -- a Settle up button for money that was no longer owed.
 */
describe('useSettlements', () => {
  let client: QueryClient
  let invalidated: unknown[][]

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }

  beforeEach(() => {
    list.mockReset()
    invalidated = []
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const original = client.invalidateQueries.bind(client)
    vi.spyOn(client, 'invalidateQueries').mockImplementation((filters) => {
      invalidated.push((filters?.queryKey ?? []) as unknown[])
      return original(filters)
    })
  })

  it('refreshes the balances when a settlement changes under it', async () => {
    list.mockResolvedValueOnce(row('PENDING')).mockResolvedValue(row('CONFIRMED'))
    const { result, rerender } = renderHook(() => useSettlements('g1'), { wrapper })

    await waitFor(() => expect(result.current.data?.[0]?.status).toBe('PENDING'))
    // The first load refreshes nothing: everything else is loading anyway. Gated on the
    // data having arrived, not on the effect having run -- the render before the fetch
    // lands has no settlements, and treating that as the baseline made the real list
    // look like a change and refreshed the whole screen on every page load.
    expect(invalidated).toHaveLength(0)

    await client.refetchQueries()
    rerender()

    await waitFor(() => expect(result.current.data?.[0]?.status).toBe('CONFIRMED'))
    await waitFor(() => {
      expect(invalidated).toContainEqual(expenseKeys.balances('g1'))
      expect(invalidated).toContainEqual(expenseKeys.simplified('g1'))
      expect(invalidated).toContainEqual(expenseKeys.activity('g1'))
      expect(invalidated).toContainEqual(expenseKeys.myNetPosition())
    })
  })

  it('ignores a change that does not move the ledger', async () => {
    // A write that bumps the version without changing the status leaves the balances
    // exactly as they were. Keying on the statuses rather than on the array means a poll
    // like this is free; keying on identity would refetch three queries for nothing.
    list.mockResolvedValueOnce(row('PENDING', 1)).mockResolvedValue(row('PENDING', 2))
    const { result, rerender } = renderHook(() => useSettlements('g1'), { wrapper })

    await waitFor(() => expect(result.current.data?.[0]?.version).toBe(1))
    await client.refetchQueries()
    rerender()

    await waitFor(() => expect(result.current.data?.[0]?.version).toBe(2))
    expect(invalidated).toHaveLength(0)
  })
})
