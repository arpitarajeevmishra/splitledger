import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Balances, Expense, ExpenseSplit, GroupDetail } from '../../api/types'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ExpenseDetail } from './ExpenseDetail'

const { useExpense, useBalances } = vi.hoisted(() => ({
  useExpense: vi.fn(),
  useBalances: vi.fn(),
}))
vi.mock('./useExpenses', () => ({ useExpense, useBalances }))

const ALICE = 'u-alice'
const BOB = 'u-bob'
const CARA = 'u-cara'

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
    { userId: CARA, name: 'Cara Dey', role: 'MEMBER' },
  ],
} as unknown as GroupDetail

function split(userId: string, owedPaise: number, inputValue: number | null = null): ExpenseSplit {
  return { userId, owedPaise, inputValue }
}

function expense(overrides: Partial<Expense> = {}): Expense {
  return {
    id: 'e1',
    groupId: 'g1',
    description: 'Dinner at Mahesh',
    amountPaise: 90_000,
    currency: 'INR',
    paidBy: ALICE,
    expenseDate: '2026-10-20',
    splitType: 'EQUAL',
    status: 'ACTIVE',
    createdAt: '2026-10-20T10:00:00Z',
    version: 1,
    splits: [split(ALICE, 30_000), split(BOB, 30_000), split(CARA, 30_000)],
    ...overrides,
  }
}

function loaded(data: Expense) {
  useExpense.mockReturnValue({ data, isPending: false, isError: false, error: null })
}

function pairwise(...debts: Balances['pairwise']) {
  useBalances.mockReturnValue({ data: { net: [], pairwise: debts } as Balances })
}

function open() {
  return renderWithProviders(<ExpenseDetail group={GROUP} expenseId="e1" onClose={() => {}} />)
}

/**
 * The screen that answers "is this one paid?".
 *
 * It cannot answer it as asked -- no payment in this product belongs to an expense -- so
 * what it must not do is imply otherwise. These assert the sentences, not the mechanism:
 * a breakdown that computes the right coverage and then words it as a per-expense receipt
 * would pass every test in coverage.test.ts and still mislead the person reading it.
 */
describe('ExpenseDetail', () => {
  beforeEach(() => {
    useExpense.mockReset()
    useBalances.mockReset()
    pairwise()
  })

  it('shows every share, with who paid and when', () => {
    loaded(expense())
    open()

    expect(screen.getByRole('dialog')).toHaveTextContent('Dinner at Mahesh')
    expect(screen.getByRole('dialog')).toHaveTextContent('₹900.00 paid by Alice Ray')
    for (const name of ['Alice Ray', 'Bob Singh', 'Cara Dey']) {
      expect(screen.getByText(name)).toBeInTheDocument()
    }
    expect(screen.getAllByText('₹300.00')).toHaveLength(3)
  })

  it('says everyone is square, naming the person they are square with', () => {
    loaded(expense())
    open()

    // Naming the payer is the whole point: "settled" on its own invites the reading that
    // this expense was paid off, which is not a thing that happens here.
    expect(screen.getByRole('status')).toHaveTextContent(
      'Everyone who shared this is square with Alice Ray.',
    )
    expect(screen.getAllByText('settled')).toHaveLength(2)
    expect(screen.getByText('paid it')).toBeInTheDocument()
  })

  it('counts who is still owing, and shows what they owe in total', () => {
    loaded(expense())
    pairwise({ from: BOB, to: ALICE, amountPaise: 75_000 })
    open()

    expect(screen.getByRole('status')).toHaveTextContent('1 of 2 still owe Alice Ray.')
    // ₹750 against a ₹300 share: the figure is deliberately not this expense's, and
    // "in all" is what stops it reading as the share being underpaid.
    expect(screen.getByText('owes ₹750.00 in all')).toBeInTheDocument()
  })

  it('says a voided expense was reversed instead of calling it settled', () => {
    loaded(expense({ status: 'VOIDED' }))
    pairwise({ from: BOB, to: ALICE, amountPaise: 75_000 })
    open()

    // Green "settled" here would be the worst available wording: it reads as the money
    // having been paid, when in fact the expense was taken back.
    expect(screen.getByRole('status')).toHaveTextContent(
      'these shares were reversed, so nobody owes anything from this expense',
    )
    expect(screen.queryByText('settled')).not.toBeInTheDocument()
    expect(screen.getAllByText('reversed')).toHaveLength(3)
  })

  it('does not claim anything while the balances are still loading', () => {
    loaded(expense())
    useBalances.mockReturnValue({ data: undefined })
    open()

    expect(screen.getByRole('status')).toHaveTextContent('Checking who is still square')
    expect(screen.queryByText('settled')).not.toBeInTheDocument()
  })

  it('explains that payments settle people, not expenses', () => {
    loaded(expense())
    open()

    // The question this screen gets asked. Leaving the answer in a decision record and
    // off the screen is how the misreading survives.
    expect(screen.getByRole('dialog')).toHaveTextContent(
      'Payments clear what one person owes another, not individual expenses',
    )
  })

  it('shows the input behind an uneven share', () => {
    loaded(
      expense({
        splitType: 'PERCENT',
        splits: [split(ALICE, 45_000, 5000), split(BOB, 27_000, 3000), split(CARA, 18_000, 2000)],
      }),
    )
    open()

    // Without this a 50/30/20 split is three unexplained numbers, and the only way to
    // check it is to divide them yourself.
    expect(screen.getByText('(50%)')).toBeInTheDocument()
    expect(screen.getByText('(30%)')).toBeInTheDocument()
  })

  it('reports a failure to load instead of an empty panel', () => {
    useExpense.mockReturnValue({ data: undefined, isPending: false, isError: true, error: null })
    open()

    expect(screen.getByRole('alert')).toHaveTextContent('Could not load that expense.')
  })
})
