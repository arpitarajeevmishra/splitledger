import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppNotification, NotificationType } from '../../api/types'
import { renderWithProviders } from '../../test/renderWithProviders'
import { NotificationBell } from './NotificationBell'

const { useUnreadCount, useNotifications, useMarkRead, useMarkAllRead, navigate } = vi.hoisted(
  () => ({
    useUnreadCount: vi.fn(),
    useNotifications: vi.fn(),
    useMarkRead: vi.fn(),
    useMarkAllRead: vi.fn(),
    navigate: vi.fn(),
  }),
)
vi.mock('./useNotifications', () => ({
  useUnreadCount,
  useNotifications,
  useMarkRead,
  useMarkAllRead,
}))
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigate,
}))

function notification(
  id: string,
  type: NotificationType = 'EXPENSE_ADDED',
  readAt: string | null = null,
): AppNotification {
  return {
    id,
    type,
    readAt,
    createdAt: '2026-10-25T12:00:00Z',
    message: {
      groupId: 'g1',
      groupName: 'Flat 402',
      actorId: 'u1',
      actorName: 'Alice Ray',
      description: 'Dinner',
      amountPaise: 90_000,
    },
  }
}

describe('NotificationBell', () => {
  let markRead: ReturnType<typeof vi.fn>
  let markAllRead: ReturnType<typeof vi.fn>

  beforeEach(() => {
    markRead = vi.fn()
    markAllRead = vi.fn()
    navigate.mockReset()
    useMarkRead.mockReturnValue({ mutate: markRead, isPending: false })
    useMarkAllRead.mockReturnValue({ mutate: markAllRead, isPending: false })
    useUnreadCount.mockReturnValue({ data: 0 })
    useNotifications.mockReturnValue({
      data: [],
      isPending: false,
      isError: false,
      error: null,
    })
  })

  it('shows the unread count, and says so to a screen reader', () => {
    useUnreadCount.mockReturnValue({ data: 3 })
    renderWithProviders(<NotificationBell />)

    expect(screen.getByRole('button', { name: 'Notifications, 3 unread' })).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('shows no badge when there is nothing unread', () => {
    renderWithProviders(<NotificationBell />)

    // A badge reading "0" is worse than none: it draws the eye to say nothing happened.
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })

  it('caps the badge rather than letting it break the header', () => {
    useUnreadCount.mockReturnValue({ data: 143 })
    renderWithProviders(<NotificationBell />)

    expect(screen.getByText('99+')).toBeInTheDocument()
  })

  it('lists the notifications once opened', async () => {
    useUnreadCount.mockReturnValue({ data: 1 })
    useNotifications.mockReturnValue({
      data: [notification('n1')],
      isPending: false,
      isError: false,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))

    expect(screen.getByText('Alice Ray added Dinner · ₹900.00')).toBeInTheDocument()
    expect(screen.getByText(/Flat 402/)).toBeInTheDocument()
  })

  it('does not mark anything read just because it was opened', async () => {
    useUnreadCount.mockReturnValue({ data: 1 })
    useNotifications.mockReturnValue({
      data: [notification('n1')],
      isPending: false,
      isError: false,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))

    // Looking at a list is not reading the things in it. A bell that empties itself on
    // open makes "what did I miss" unanswerable the second time you ask.
    expect(markRead).not.toHaveBeenCalled()
    expect(markAllRead).not.toHaveBeenCalled()
  })

  it('marks one read and opens its group when clicked', async () => {
    useUnreadCount.mockReturnValue({ data: 1 })
    useNotifications.mockReturnValue({
      data: [notification('n1')],
      isPending: false,
      isError: false,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))
    await userEvent.click(screen.getByText('Alice Ray added Dinner · ₹900.00'))

    expect(markRead).toHaveBeenCalledWith('n1')
    expect(navigate).toHaveBeenCalledWith('/groups/g1')
  })

  it('does not re-mark one that was already read', async () => {
    useUnreadCount.mockReturnValue({ data: 0 })
    useNotifications.mockReturnValue({
      data: [notification('n1', 'EXPENSE_ADDED', '2026-10-25T13:00:00Z')],
      isPending: false,
      isError: false,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))
    await userEvent.click(screen.getByText('Alice Ray added Dinner · ₹900.00'))

    // A write per click on an already-read row is a request that can only fail or do
    // nothing, and it still navigates either way.
    expect(markRead).not.toHaveBeenCalled()
    expect(navigate).toHaveBeenCalledWith('/groups/g1')
  })

  it('clears the bell on request', async () => {
    useUnreadCount.mockReturnValue({ data: 2 })
    useNotifications.mockReturnValue({
      data: [notification('n1'), notification('n2')],
      isPending: false,
      isError: false,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Mark all read' }))

    expect(markAllRead).toHaveBeenCalled()
  })

  it('offers nothing to clear when nothing is unread', async () => {
    useNotifications.mockReturnValue({
      data: [notification('n1', 'EXPENSE_ADDED', '2026-10-25T13:00:00Z')],
      isPending: false,
      isError: false,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))

    expect(screen.queryByRole('button', { name: 'Mark all read' })).not.toBeInTheDocument()
  })

  it('says there is nothing rather than showing an empty box', async () => {
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))

    expect(screen.getByText('Nothing yet.')).toBeInTheDocument()
  })

  it('reports a failure to load instead of looking empty', async () => {
    useNotifications.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))

    // An error rendered as "nothing yet" is the worst outcome: it reads as reassurance.
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load your notifications.')
    expect(screen.queryByText('Nothing yet.')).not.toBeInTheDocument()
  })

  it('closes on Escape', async () => {
    useNotifications.mockReturnValue({
      data: [notification('n1')],
      isPending: false,
      isError: false,
      error: null,
    })
    renderWithProviders(<NotificationBell />)

    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }))
    expect(screen.getByText('Alice Ray added Dinner · ₹900.00')).toBeInTheDocument()

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByText('Alice Ray added Dinner · ₹900.00')).not.toBeInTheDocument()
  })
})
