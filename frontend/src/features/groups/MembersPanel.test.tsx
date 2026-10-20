import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupDetail, Role } from '../../api/types'
import { renderWithProviders, SOMEONE } from '../../test/renderWithProviders'
import { MembersPanel } from './MembersPanel'

const { removeMember } = vi.hoisted(() => ({ removeMember: vi.fn() }))
vi.mock('./useGroups', () => ({
  useRemoveMember: () => ({ mutateAsync: removeMember, isPending: false }),
}))

function group(yourRole: Role): GroupDetail {
  return {
    id: 'g1',
    name: 'Flat 402',
    currency: 'INR',
    yourRole,
    createdAt: '2026-01-01T10:00:00Z',
    members: [
      { userId: SOMEONE.id, name: 'Alice Ray', role: yourRole, joinedAt: '2026-01-01T10:00:00Z' },
      { userId: 'user-2', name: 'Bob Stone', role: 'MEMBER', joinedAt: '2026-01-02T10:00:00Z' },
    ],
  }
}

describe('MembersPanel', () => {
  beforeEach(() => {
    removeMember.mockReset()
    removeMember.mockResolvedValue(undefined)
  })

  it('offers Leave for yourself and Remove for others when you are an admin', () => {
    renderWithProviders(<MembersPanel group={group('ADMIN')} youId={SOMEONE.id} />)

    expect(screen.getByRole('button', { name: 'Leave' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument()
  })

  it('offers a member only the option to leave, never to remove anyone else', () => {
    renderWithProviders(<MembersPanel group={group('MEMBER')} youId={SOMEONE.id} />)

    expect(screen.getByRole('button', { name: 'Leave' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
  })

  it('confirms before removing, and does nothing if you cancel', async () => {
    renderWithProviders(<MembersPanel group={group('ADMIN')} youId={SOMEONE.id} />)

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    expect(await screen.findByRole('dialog')).toHaveTextContent('Remove Bob Stone from Flat 402?')

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(removeMember).not.toHaveBeenCalled()
  })

  it('removes the member once confirmed', async () => {
    renderWithProviders(<MembersPanel group={group('ADMIN')} youId={SOMEONE.id} />)

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))

    expect(removeMember).toHaveBeenCalledWith('user-2')
  })

  it('surfaces a refusal the user cannot otherwise account for', async () => {
    const error = new AxiosError('failed')
    error.response = {
      status: 422,
      statusText: '',
      headers: new AxiosHeaders(),
      config: { headers: new AxiosHeaders() },
      data: { code: 'MEMBER_HAS_OUTSTANDING_BALANCE' },
    }
    removeMember.mockRejectedValue(error)
    renderWithProviders(<MembersPanel group={group('ADMIN')} youId={SOMEONE.id} />)

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Settle up before leaving or removing someone.',
    )
  })
})
