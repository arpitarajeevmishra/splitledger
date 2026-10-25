import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Expense, ExpenseSummary, GroupDetail } from '../../api/types'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ExpenseList } from './ExpenseList'

const { useExpenses, useVoidExpense, useExpense, useBalances } = vi.hoisted(() => ({
  useExpenses: vi.fn(),
  useVoidExpense: vi.fn(),
  useExpense: vi.fn(),
  useBalances: vi.fn(),
}))
vi.mock('./useExpenses', () => ({
  useExpenses,
  useVoidExpense,
  useExpense,
  useBalances,
  useCreateExpense: () => ({ mutateAsync: vi.fn(), isPending: false }),
  expenseKeys: { list: () => ['expenses'] },
}))

const ALICE = 'u-alice'
const BOB = 'u-bob'

const GROUP = {
  id: 'g1',
  name: 'Flat 402',
  currency: 'INR',
  yourRole: 'ADMIN',
  archivedAt: null,
  createdAt: '2026-10-01T10:00:00Z',
  members: [
    { userId: ALICE, name: 'Alice Ray', role: 'ADMIN' },
    { userId: BOB, name: 'Bob Singh', role: 'MEMBER' },
  ],
} as unknown as GroupDetail

const SUMMARY: ExpenseSummary = {
  id: 'e1',
  description: 'Dinner at Mahesh',
  amountPaise: 90_000,
  paidBy: ALICE,
  expenseDate: '2026-10-20',
  splitType: 'EQUAL',
  status: 'ACTIVE',
  version: 1,
}

const DETAIL = {
  ...SUMMARY,
  groupId: 'g1',
  currency: 'INR',
  createdAt: '2026-10-20T10:00:00Z',
  splits: [
    { userId: ALICE, owedPaise: 45_000, inputValue: null },
    { userId: BOB, owedPaise: 45_000, inputValue: null },
  ],
} as Expense

describe('ExpenseList', () => {
  beforeEach(() => {
    useExpenses.mockReturnValue({ data: [SUMMARY], isPending: false, isError: false })
    useVoidExpense.mockReturnValue({ mutateAsync: vi.fn(), isPending: false })
    useExpense.mockReturnValue({ data: DETAIL, isPending: false, isError: false, error: null })
    useBalances.mockReturnValue({ data: { net: [], pairwise: [] } })
  })

  it('opens the breakdown when an expense is clicked', async () => {
    renderWithProviders(<ExpenseList group={GROUP} />)

    // The reported gap: with many expenses there was no way to look into one. The row
    // showed a total and a payer and nothing about who still owes for it.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Dinner at Mahesh/ }))

    expect(await screen.findByRole('dialog')).toHaveTextContent('Bob Singh')
  })

  it('keeps Void out of the row that opens the breakdown', async () => {
    const mutateAsync = vi.fn()
    useVoidExpense.mockReturnValue({ mutateAsync, isPending: false })
    renderWithProviders(<ExpenseList group={GROUP} />)

    // Void sits inside the row. Were the row itself the control, every press of it would
    // void an expense and open its breakdown behind the result.
    await userEvent.click(screen.getByRole('button', { name: 'Void' }))

    expect(mutateAsync).toHaveBeenCalledWith({ expenseId: 'e1', version: 1 })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
