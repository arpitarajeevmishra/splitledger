import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CreateExpense, GroupDetail } from '../../api/types'
import { renderWithProviders, SOMEONE } from '../../test/renderWithProviders'
import { ExpenseForm } from './ExpenseForm'

const { createExpense } = vi.hoisted(() => ({ createExpense: vi.fn() }))
vi.mock('./useExpenses', () => ({
  useCreateExpense: () => ({ mutateAsync: createExpense, isPending: false }),
}))

const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'

const GROUP: GroupDetail = {
  id: 'g1',
  name: 'Flat 402',
  currency: 'INR',
  yourRole: 'ADMIN',
  createdAt: '2026-01-01T10:00:00Z',
  members: [
    { userId: SOMEONE.id, name: 'Alice Ray', role: 'ADMIN', joinedAt: '2026-01-01T10:00:00Z' },
    { userId: BOB, name: 'Bob Stone', role: 'MEMBER', joinedAt: '2026-01-02T10:00:00Z' },
    { userId: CAROL, name: 'Carol Vance', role: 'MEMBER', joinedAt: '2026-01-03T10:00:00Z' },
  ],
}

function renderForm() {
  renderWithProviders(<ExpenseForm group={GROUP} open onClose={vi.fn()} />)
}

const addButton = () => screen.getByRole('button', { name: /Add expense/ })

/** The payload of the nth create call, asserted non-empty so the types stay honest. */
function sentExpense(call = 0) {
  const args = createExpense.mock.calls[call]
  expect(args).toBeDefined()
  return args![0] as { expense: CreateExpense; idempotencyKey: string }
}

describe('ExpenseForm', () => {
  beforeEach(() => {
    createExpense.mockReset()
    createExpense.mockResolvedValue({ id: 'e1' })
  })

  it('cannot be submitted before an amount is entered', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Dinner')

    expect(addButton()).toBeDisabled()
  })

  it('submits an equal split once description and amount are there', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Dinner')
    await userEvent.type(screen.getByLabelText('Amount'), '900')

    expect(addButton()).toBeEnabled()
    await userEvent.click(addButton())

    expect(createExpense).toHaveBeenCalledTimes(1)
    const sent = sentExpense()
    expect(sent.expense.amountPaise).toBe(90_000)
    expect(sent.expense.splitType).toBe('EQUAL')
    expect(sent.expense.participants).toHaveLength(3)
    // A fresh key per submission, so a retry can reuse it and a new expense cannot.
    expect(sent.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('shows what is still unaccounted for on an exact split', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Dinner')
    await userEvent.type(screen.getByLabelText('Amount'), '900')
    await userEvent.click(screen.getByRole('button', { name: 'Exact amounts' }))

    await userEvent.type(screen.getByLabelText('Exact amounts for Alice Ray'), '300')
    await userEvent.type(screen.getByLabelText('Exact amounts for Bob Stone'), '300')

    // 300 of 900 still unassigned, and the counter says so rather than waiting for submit.
    const counter = screen.getByRole('status')
    expect(within(counter).getByText(/Remaining/i)).toBeInTheDocument()
    expect(counter).toHaveTextContent('\u20b9300.00')
    expect(addButton()).toBeDisabled()
  })

  it('cannot submit an exact split that does not add up to the paisa', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Dinner')
    await userEvent.type(screen.getByLabelText('Amount'), '900')
    await userEvent.click(screen.getByRole('button', { name: 'Exact amounts' }))

    for (const name of ['Alice Ray', 'Bob Stone', 'Carol Vance']) {
      await userEvent.type(screen.getByLabelText(`Exact amounts for ${name}`), '299.99')
    }

    // Three paise short. The server would refuse it; this refuses it first.
    expect(addButton()).toBeDisabled()
    await userEvent.click(addButton())
    expect(createExpense).not.toHaveBeenCalled()
  })

  it('enables submit once an exact split balances exactly', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Dinner')
    await userEvent.type(screen.getByLabelText('Amount'), '900')
    await userEvent.click(screen.getByRole('button', { name: 'Exact amounts' }))

    for (const name of ['Alice Ray', 'Bob Stone', 'Carol Vance']) {
      await userEvent.type(screen.getByLabelText(`Exact amounts for ${name}`), '300')
    }

    expect(screen.getByRole('status')).toHaveTextContent(/Adds up/i)
    expect(addButton()).toBeEnabled()
  })

  it('says when somebody has to take the extra paisa', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Sweets')
    // 10 paise among three does not divide; one person takes an extra.
    await userEvent.type(screen.getByLabelText('Amount'), '0.10')

    expect(screen.getByText(/takes an extra\s+paisa/i)).toBeInTheDocument()
    // Still submittable: the split is complete, just uneven by a paisa.
    expect(addButton()).toBeEnabled()
  })

  it('drops someone from the split when they are unticked', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Dinner')
    await userEvent.type(screen.getByLabelText('Amount'), '900')
    await userEvent.click(screen.getByLabelText('Carol Vance'))

    await userEvent.click(addButton())

    expect(sentExpense().expense.participants).toHaveLength(2)
  })

  it('refuses an amount with more precision than a paisa', async () => {
    renderForm()
    await userEvent.type(screen.getByLabelText('Description'), 'Dinner')
    await userEvent.type(screen.getByLabelText('Amount'), '1.005')

    expect(await screen.findByText(/smaller than a paisa/i)).toBeInTheDocument()
    expect(addButton()).toBeDisabled()
  })
})
