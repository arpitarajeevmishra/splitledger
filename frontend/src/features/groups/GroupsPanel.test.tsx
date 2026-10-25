import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupSummary } from '../../api/types'
import { renderWithProviders } from '../../test/renderWithProviders'
import { GroupsPanel } from './GroupsPanel'

const { useGroups, useCreateGroup, useArchivedGroups } = vi.hoisted(() => ({
  useArchivedGroups: vi.fn(),
  useGroups: vi.fn(),
  useCreateGroup: vi.fn(),
}))
vi.mock('./useGroups', () => ({ useGroups, useCreateGroup, useArchivedGroups }))

const GROUPS: GroupSummary[] = [
  {
    id: 'g1',
    name: 'Flat 402',
    currency: 'INR',
    yourRole: 'ADMIN',
    memberCount: 4,
    archived: false,
    createdAt: '2026-01-01T10:00:00Z',
  },
  {
    id: 'g2',
    name: 'Goa trip',
    currency: 'INR',
    yourRole: 'MEMBER',
    memberCount: 1,
    archived: false,
    createdAt: '2026-02-01T10:00:00Z',
  },
]

describe('GroupsPanel', () => {
  beforeEach(() => {
    useArchivedGroups.mockReturnValue({ data: [] as GroupSummary[] })
    useCreateGroup.mockReturnValue({ mutateAsync: vi.fn(), isPending: false })
  })

  it('lists groups with their size and links to each', () => {
    useGroups.mockReturnValue({ data: GROUPS, isPending: false, isError: false })
    renderWithProviders(<GroupsPanel />)

    expect(screen.getByRole('link', { name: /Flat 402/ })).toHaveAttribute('href', '/groups/g1')
    expect(screen.getByText(/4 members · admin/)).toBeInTheDocument()
    // Singular, because "1 members" is the kind of detail that makes a product look unfinished.
    expect(screen.getByText(/^1 member$/)).toBeInTheDocument()
  })

  it('renders a real ellipsis while loading, not an escape sequence', () => {
    useGroups.mockReturnValue({ data: undefined, isPending: true, isError: false })
    renderWithProviders(<GroupsPanel />)

    // A \u2026 escape is interpreted inside a JS string literal but is plain text in JSX,
    // where it renders as six literal characters. Caught by running the app, not by a
    // test, so here is the test.
    expect(screen.getByText('Loading groups\u2026')).toBeInTheDocument()
    expect(screen.queryByText(/\\u[0-9a-f]{4}/i)).not.toBeInTheDocument()
  })

  it('says there are none rather than showing an empty box', () => {
    useGroups.mockReturnValue({ data: [], isPending: false, isError: false })
    renderWithProviders(<GroupsPanel />)

    expect(screen.getByText('No groups yet.')).toBeInTheDocument()
  })

  it('reports a failure to load instead of looking empty', () => {
    useGroups.mockReturnValue({ data: undefined, isPending: false, isError: true, error: null })
    renderWithProviders(<GroupsPanel />)

    // An error rendered as "no groups" is the worst outcome: it reads as data loss.
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load your groups.')
    expect(screen.queryByText('No groups yet.')).not.toBeInTheDocument()
  })

  it('opens the create dialog', async () => {
    useGroups.mockReturnValue({ data: [], isPending: false, isError: false })
    renderWithProviders(<GroupsPanel />)

    await userEvent.click(screen.getByRole('button', { name: 'New group' }))

    expect(await screen.findByRole('dialog')).toHaveTextContent('New group')
    expect(screen.getByLabelText('Group name')).toBeInTheDocument()
  })

  it('keeps archived groups out of the list until asked for', async () => {
    // The point of archiving is that the list stops showing it. A toggle that fetched
    // them anyway would make the archive a second place to be reminded of the group.
    useArchivedGroups.mockReturnValue({
      data: [{ ...GROUPS[0]!, id: 'g9', name: 'Old flat', archived: true }],
    })
    renderWithProviders(<GroupsPanel />)

    expect(screen.queryByText('Old flat')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /show archived/i }))

    expect(screen.getByText('Old flat')).toBeInTheDocument()
  })

  it('says so when there is nothing archived', async () => {
    useArchivedGroups.mockReturnValue({ data: [] })
    renderWithProviders(<GroupsPanel />)

    await userEvent.click(screen.getByRole('button', { name: /show archived/i }))

    expect(screen.getByText(/nothing archived/i)).toBeInTheDocument()
  })
})
