const DATE = new Intl.DateTimeFormat(undefined, {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

export function formatDate(iso: string): string {
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? '--' : DATE.format(parsed)
}

/**
 * "in 6 days" / "expired". Invites are the only place this is needed, and what matters to
 * the reader is whether the link is still good, not the exact timestamp.
 */
export function expiryLabel(iso: string, now: Date = new Date()): string {
  const expires = new Date(iso)
  if (Number.isNaN(expires.getTime())) {
    return ''
  }
  const days = Math.ceil((expires.getTime() - now.getTime()) / 86_400_000)
  if (days <= 0) {
    return 'expired'
  }
  return days === 1 ? 'expires tomorrow' : `expires in ${days} days`
}

/**
 * "just now" / "4h ago" / "3 Oct". What a notification needs: recent things read as elapsed
 * time, and anything older than a week is a date, because "9 days ago" is a number nobody
 * converts into a day of the week.
 */
export function agoLabel(iso: string, now: Date = new Date()): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) {
    return ''
  }
  const seconds = Math.floor((now.getTime() - at.getTime()) / 1000)
  // A clock a little behind the server's would otherwise render "in -3 seconds".
  if (seconds < 60) {
    return 'just now'
  }
  if (seconds < 3_600) {
    return `${Math.floor(seconds / 60)}m ago`
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3_600)}h ago`
  }
  if (seconds < 604_800) {
    return `${Math.floor(seconds / 86_400)}d ago`
  }
  return formatDate(iso)
}
