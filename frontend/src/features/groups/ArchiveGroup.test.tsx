import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupDetail } from '../../api/types'
import { renderWithProviders, SOMEONE } from '../../test/renderWithProviders'
import { ArchiveGroup } from './ArchiveGroup'

const { archive, unarchive } = vi.hoisted(() => ({ archive: vi.fn(), unarchive: vi.fn() }))
vi.mock('./useGroups', () => ({
  useArchiveGroup: () => ({ mutateAsync: archive, isPending: false }),
  useUnarchiveGroup: () => ({ mutateAsync: unarchive, isPending: false }),
}))

const GROUP: GroupDetail = {
  id: 'g1',
  name: 'Old flat',
  currency: 'INR',
  yourRole: 'ADMIN',
  createdAt: '2026-01-01T10:00:00Z',
  members: [
    { userId: SOMEONE.id, name: 'Alice Ray', role: 'ADMIN', joinedAt: '2026-01-01T10:00:00Z' },
  ],
  archivedAt: null,
}

describe('ArchiveGroup', () => {
  beforeEach(() => {
    archive.mockReset()
    archive.mockResolvedValue({ ...GROUP, archivedAt: '2026-10-25T10:00:00Z' })
    unarchive.mockReset()
    unarchive.mockResolvedValue(GROUP)
  })

  it('asks before archiving, and says nothing is deleted', async () => {
    // The word "archive" does a lot of work here. Someone pressing it is usually looking
    // for "delete", so the dialog has to say what actually happens before they commit.
    renderWithProviders(<ArchiveGroup group={GROUP} />)

    await userEvent.click(screen.getByRole('button', { name: 'Archive group' }))

    expect(screen.getByText(/nothing is deleted/i)).toBeInTheDocument()
    expect(screen.getByText(/restore it at any time/i)).toBeInTheDocument()
    expect(archive).not.toHaveBeenCalled()
  })

  it('archives once confirmed', async () => {
    renderWithProviders(<ArchiveGroup group={GROUP} />)

    await userEvent.click(screen.getByRole('button', { name: 'Archive group' }))
    const [, confirm] = screen.getAllByRole('button', { name: 'Archive group' })
    await userEvent.click(confirm!)

    expect(archive).toHaveBeenCalledWith('g1')
  })

  it('does nothing if the dialog is cancelled', async () => {
    renderWithProviders(<ArchiveGroup group={GROUP} />)

    await userEvent.click(screen.getByRole('button', { name: 'Archive group' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(archive).not.toHaveBeenCalled()
  })

  it('offers to restore an archived group, with no confirmation', async () => {
    // Restoring is not destructive, so making someone confirm it would be ceremony.
    renderWithProviders(<ArchiveGroup group={{ ...GROUP, archivedAt: '2026-10-25T10:00:00Z' }} />)

    await userEvent.click(screen.getByRole('button', { name: 'Restore group' }))

    expect(unarchive).toHaveBeenCalledWith('g1')
    expect(archive).not.toHaveBeenCalled()
  })

  it('shows why it failed rather than closing silently', async () => {
    archive.mockRejectedValue(new Error('nope'))
    renderWithProviders(<ArchiveGroup group={GROUP} />)

    await userEvent.click(screen.getByRole('button', { name: 'Archive group' }))
    const [, confirm] = screen.getAllByRole('button', { name: 'Archive group' })
    await userEvent.click(confirm!)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
