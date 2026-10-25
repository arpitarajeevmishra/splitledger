import { describe, expect, it } from 'vitest'
import { agoLabel, expiryLabel, formatDate } from './format'

describe('formatDate', () => {
  it('formats an ISO instant', () => {
    expect(formatDate('2026-01-15T10:00:00Z')).toMatch(/2026/)
  })

  it('does not throw on a value the server never sent', () => {
    expect(formatDate('not-a-date')).toBe('--')
  })
})

describe('expiryLabel', () => {
  const now = new Date('2026-01-01T10:00:00Z')

  it('counts the days left', () => {
    expect(expiryLabel('2026-01-07T10:00:00Z', now)).toBe('expires in 6 days')
  })

  it('reads naturally at one day', () => {
    expect(expiryLabel('2026-01-02T09:00:00Z', now)).toBe('expires tomorrow')
  })

  it('says expired once the date has passed', () => {
    expect(expiryLabel('2025-12-31T10:00:00Z', now)).toBe('expired')
  })
})

describe('agoLabel', () => {
  const NOW = new Date('2026-10-25T12:00:00Z')

  it('reads recent things as elapsed time', () => {
    expect(agoLabel('2026-10-25T11:58:00Z', NOW)).toBe('2m ago')
    expect(agoLabel('2026-10-25T08:00:00Z', NOW)).toBe('4h ago')
    expect(agoLabel('2026-10-22T12:00:00Z', NOW)).toBe('3d ago')
  })

  it('says just now rather than a negative age', () => {
    // A browser clock a little behind the server's makes createdAt look like the future,
    // and "in -3 seconds" is the kind of detail that makes people distrust the numbers.
    expect(agoLabel('2026-10-25T12:00:30Z', NOW)).toBe('just now')
    expect(agoLabel('2026-10-25T11:59:30Z', NOW)).toBe('just now')
  })

  it('switches to a date once elapsed time stops helping', () => {
    // "9d ago" is a number nobody converts into a day of the week.
    expect(agoLabel('2026-10-10T12:00:00Z', NOW)).toBe(formatDate('2026-10-10T12:00:00Z'))
  })

  it('renders nothing for an unparseable timestamp', () => {
    expect(agoLabel('not a date', NOW)).toBe('')
  })
})
