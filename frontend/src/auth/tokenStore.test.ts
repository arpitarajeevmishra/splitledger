import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStore } from './tokenStore'

afterEach(() => {
  tokenStore.clear()
  vi.restoreAllMocks()
})

describe('tokenStore', () => {
  it('holds and clears the access token', () => {
    expect(tokenStore.get()).toBeNull()
    tokenStore.set('a-token')
    expect(tokenStore.get()).toBe('a-token')
    tokenStore.clear()
    expect(tokenStore.get()).toBeNull()
  })

  it('never writes the token to browser storage', () => {
    const local = vi.spyOn(window.localStorage, 'setItem')
    const session = vi.spyOn(window.sessionStorage, 'setItem')

    tokenStore.set('a-token')

    // ADR-011: the access token is held in memory only, so it is never sitting in storage
    // for a later or passive read, and it is gone when the tab closes.
    expect(local).not.toHaveBeenCalled()
    expect(session).not.toHaveBeenCalled()
    // Asserting the token is absent rather than that storage is empty: emptiness depends
    // on everything else in the environment, so it would fail for reasons unrelated to
    // this module. What matters is that the token itself is not retrievable from storage.
    const storedValues = Object.keys(window.localStorage).map((key) =>
      window.localStorage.getItem(key),
    )
    expect(storedValues).not.toContain('a-token')
  })
})
