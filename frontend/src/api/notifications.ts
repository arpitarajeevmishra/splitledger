import type { AxiosInstance } from 'axios'
import { api } from './client'
import type { AppNotification } from './types'

export function notificationsApi(client: AxiosInstance = api) {
  return {
    list: (page = 0, size = 30) =>
      client
        .get<AppNotification[]>('/notifications', { params: { page, size } })
        .then((r) => r.data),

    /**
     * Its own endpoint, not a count of the list: this is what the badge polls, and
     * fetching thirty rows to render the number 3 would make the most frequent call the
     * most expensive one.
     */
    unreadCount: () =>
      client.get<{ unread: number }>('/notifications/unread-count').then((r) => r.data.unread),

    markRead: (notificationId: string) =>
      client.post<AppNotification>(`/notifications/${notificationId}/read`).then((r) => r.data),

    markAllRead: () =>
      client.post<{ cleared: number }>('/notifications/read').then((r) => r.data.cleared),
  }
}
