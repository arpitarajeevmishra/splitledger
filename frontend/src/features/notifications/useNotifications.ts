import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { notificationsApi } from '../../api/notifications'
import type { AppNotification } from '../../api/types'

const notifications = notificationsApi()

export const notificationKeys = {
  all: ['notifications'] as const,
  list: () => [...notificationKeys.all, 'list'] as const,
  unread: () => [...notificationKeys.all, 'unread'] as const,
}

/**
 * The badge.
 *
 * <p>Polled, and refetched on focus against the global default (D-98), because this number
 * is the one thing on screen whose whole purpose is to change while you are not looking --
 * somebody else adds an expense or confirms a payment, and nothing in this tab would
 * otherwise hear about it.
 *
 * <p>A minute, not a few seconds: a bell is not a chat, and the settlements poll already
 * demonstrated what happens when a fast interval runs forever on an idle tab.
 */
export function useUnreadCount() {
  return useQuery<number>({
    queryKey: notificationKeys.unread(),
    queryFn: notifications.unreadCount,
    refetchInterval: 60_000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  })
}

/** Fetched only while the panel is open: a closed bell has no reason to hold a list. */
export function useNotifications(enabled: boolean) {
  return useQuery<AppNotification[]>({
    queryKey: notificationKeys.list(),
    queryFn: () => notifications.list(),
    enabled,
    refetchOnWindowFocus: true,
  })
}

/**
 * Both the list and the badge, after anything that changes a read state.
 *
 * Refreshing one and not the other is how a bell comes to show "3" over a panel in which
 * everything is already read.
 */
function useReadAction<T>(action: (input: T) => Promise<unknown>) {
  const queryClient = useQueryClient()
  return useMutation<unknown, unknown, T>({
    mutationFn: action,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: notificationKeys.list() }),
        queryClient.invalidateQueries({ queryKey: notificationKeys.unread() }),
      ])
    },
  })
}

export function useMarkRead() {
  return useReadAction<string>(notifications.markRead)
}

export function useMarkAllRead() {
  return useReadAction<void>(() => notifications.markAllRead())
}
