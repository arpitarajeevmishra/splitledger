import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { messageFor } from '../../api/problem'
import type { AppNotification } from '../../api/types'
import { agoLabel } from '../../lib/format'
import { notificationText } from './notificationText'
import { useMarkAllRead, useMarkRead, useNotifications, useUnreadCount } from './useNotifications'

/**
 * The bell.
 *
 * <p>A panel rather than a dialog on purpose: a modal would dim the page and trap focus to
 * show a list people glance at and dismiss. It closes on Escape and on a click outside,
 * which is what the rest of the web has taught everyone a dropdown does.
 *
 * <p>Opening does <b>not</b> mark anything read. Looking at a list is not the same as
 * having read the things in it, and a bell that silently empties itself the moment it is
 * opened makes "what did I miss" unanswerable the second time.
 */
export function NotificationBell() {
  const [open, setOpen] = useState(false)
  const { data: unread } = useUnreadCount()
  const { data: notifications, isPending, isError, error } = useNotifications(open)
  const markRead = useMarkRead()
  const markAllRead = useMarkAllRead()
  const navigate = useNavigate()
  const container = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }
    const onClick = (event: MouseEvent) => {
      if (!container.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('keydown', onKey)
    // Listened for on the way down, so a click on something that re-renders and removes
    // itself still closes the panel.
    document.addEventListener('mousedown', onClick)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onClick)
    }
  }, [open])

  function onOpen(notification: AppNotification) {
    // Marked read on the way out rather than awaited: the navigation is what the person
    // asked for, and holding it behind a POST would make the bell feel broken on a slow
    // connection. A failure here costs an unread badge, not the thing they clicked.
    if (notification.readAt === null) {
      markRead.mutate(notification.id)
    }
    setOpen(false)
    void navigate(`/groups/${notification.message.groupId}`)
  }

  const count = unread ?? 0

  return (
    <div className="relative" ref={container}>
      <button
        type="button"
        onClick={() => setOpen((wasOpen) => !wasOpen)}
        aria-expanded={open}
        aria-label={count === 0 ? 'Notifications' : `Notifications, ${count} unread`}
        className={`relative cursor-pointer rounded px-2 py-1 font-mono text-[11px] tracking-[0.06em] text-ink-dim uppercase hover:text-ink`}
      >
        Inbox
        {/* text-surface rather than text-white: --negative is a deep rose in light mode and
            a light pink in dark, so white measures 4.7:1 against one and 2.69:1 against the
            other. The surface colour flips with the theme and passes against both. */}
        {count > 0 && (
          <span className="ml-1.5 rounded-full bg-negative px-1.5 py-0.5 font-mono text-[10px] text-surface">
            {/* Capped, because a three-digit badge stops being a number and starts being a
                layout problem -- and nobody acts differently on 143 than on 99+. */}
            {count > 99 ? '99+' : count}
          </span>
        )}
      </button>

      {/* px-3 inside, rather than the px-4 every full-width panel uses: this is a 22rem
          popover, and a 16px gutter each side eats proportionally far more of the line.
          The vertical rhythm is the standard py-3, so only the horizontal axis differs. */}
      {open && (
        <div className="absolute right-0 z-20 mt-2 w-[min(22rem,calc(100vw-2rem))] rounded-lg border border-line bg-surface-1 shadow-lg">
          <div className="flex items-center justify-between border-b border-line px-3 py-2">
            <span className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
              Inbox
            </span>
            {count > 0 && (
              <button
                type="button"
                disabled={markAllRead.isPending}
                onClick={() => markAllRead.mutate()}
                className="cursor-pointer font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase hover:text-ink disabled:opacity-50"
              >
                Mark all read
              </button>
            )}
          </div>

          {isPending && <p className="px-3 py-6 text-center text-sm text-ink-dim">Loading…</p>}

          {isError && (
            <p role="alert" className="px-3 py-6 text-center text-sm text-negative">
              {messageFor(error, 'Could not load your notifications.')}
            </p>
          )}

          {notifications?.length === 0 && (
            <p className="px-3 py-6 text-center text-sm text-ink-muted">Nothing yet.</p>
          )}

          {notifications !== undefined && notifications.length > 0 && (
            <ul className="max-h-[22rem] overflow-y-auto">
              {notifications.map((notification) => (
                <li key={notification.id} className="border-b border-line last:border-b-0">
                  <button
                    type="button"
                    onClick={() => onOpen(notification)}
                    className="block w-full cursor-pointer px-3 py-3 text-left hover:bg-surface-2"
                  >
                    <span
                      className={`block text-sm ${
                        // Unread is the only thing weight is used for here, so a glance
                        // down the list answers "what is new" without reading any of it.
                        notification.readAt === null ? 'font-medium text-ink' : 'text-ink-muted'
                      }`}
                    >
                      {notificationText(notification)}
                    </span>
                    <span className="mt-0.5 block font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
                      {notification.message.groupName ?? 'A group'}
                      {' · '}
                      {agoLabel(notification.createdAt)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
