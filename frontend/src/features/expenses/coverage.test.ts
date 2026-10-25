import { describe, expect, it } from 'vitest'
import type { Debt, Expense, ExpenseSplit } from '../../api/types'
import { coverageOf } from './coverage'

const ALICE = 'u-alice'
const BOB = 'u-bob'
const CARA = 'u-cara'

function split(userId: string, owedPaise: number): ExpenseSplit {
  return { userId, owedPaise, inputValue: null }
}

function expense(overrides: Partial<Expense> = {}): Expense {
  return {
    id: 'e1',
    groupId: 'g1',
    description: 'Dinner',
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

describe('coverageOf', () => {
  it('says the payer paid it, rather than that they owe themselves', () => {
    // Their own share of their own expense is in the splits -- it is how the arithmetic
    // works out -- but it was never a debt, and a "settled" badge on it would imply
    // somebody cleared something.
    expect(coverageOf(split(ALICE, 30_000), expense(), [])).toEqual({ kind: 'PAID_IT' })
  })

  it('treats a missing pairwise row as square', () => {
    // The query omits pairs that cancel out, so absent and zero mean the same thing.
    // Read the other way round, every settled expense in the app would say "owes ₹0.00".
    expect(coverageOf(split(BOB, 30_000), expense(), [])).toEqual({ kind: 'SQUARE' })
    expect(
      coverageOf(split(BOB, 30_000), expense(), [{ from: BOB, to: ALICE, amountPaise: 0 }]),
    ).toEqual({ kind: 'SQUARE' })
  })

  it('reports what the pair still owes, not what this share was', () => {
    // 30,000 on this expense, 75,000 outstanding between them. The larger figure is the
    // honest one: no payment in this product is attributable to a single expense, so the
    // only true statement is about the pair.
    expect(
      coverageOf(split(BOB, 30_000), expense(), [{ from: BOB, to: ALICE, amountPaise: 75_000 }]),
    ).toEqual({ kind: 'OWES', paise: 75_000 })
  })

  it('does not read a debt the other way round as this person owing', () => {
    // Alice owing Bob says nothing about Bob's share of an expense Alice paid for. Matching
    // the pair without its direction would turn being owed money into owing it.
    expect(
      coverageOf(split(BOB, 30_000), expense(), [{ from: ALICE, to: BOB, amountPaise: 75_000 }]),
    ).toEqual({ kind: 'SQUARE' })
  })

  it('ignores debts to anyone other than whoever paid', () => {
    expect(
      coverageOf(split(BOB, 30_000), expense(), [{ from: BOB, to: CARA, amountPaise: 75_000 }]),
    ).toEqual({ kind: 'SQUARE' })
  })

  it('calls a voided expense reversed whatever the balances say', () => {
    // The one case with a genuine per-expense answer: its postings were reversed, so
    // nothing is owed from it even though the pair may still owe each other plenty.
    const pairwise: Debt[] = [{ from: BOB, to: ALICE, amountPaise: 75_000 }]
    expect(coverageOf(split(BOB, 30_000), expense({ status: 'VOIDED' }), pairwise)).toEqual({
      kind: 'REVERSED',
    })
  })

  it('admits to not knowing while the balances are still loading', () => {
    // Rendered as a dash. Defaulting to "settled" here would flash a false all-clear on
    // every open, which is the one wrong answer that nobody would think to check.
    expect(coverageOf(split(BOB, 30_000), expense(), undefined)).toEqual({ kind: 'UNKNOWN' })
  })
})
