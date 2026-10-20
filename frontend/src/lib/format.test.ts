import { describe, expect, it } from 'vitest'
import { expiryLabel, formatDate } from './format'

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
