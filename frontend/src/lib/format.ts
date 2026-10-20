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
