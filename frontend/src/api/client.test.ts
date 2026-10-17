import { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStore } from '../auth/tokenStore'
import { createApiClient } from './client'

function response<T>(
  config: InternalAxiosRequestConfig,
  data: T,
  status: number,
): AxiosResponse<T> {
  return { data, status, statusText: '', headers: {}, config }
}

function rejection(config: InternalAxiosRequestConfig, status: number): AxiosError {
  return new AxiosError('failed', String(status), config, null, response(config, {}, status))
}

/** Records every request so the number of refreshes and retries can be asserted exactly. */
function scriptedAdapter(
  handler: (url: string, calls: InternalAxiosRequestConfig[]) => 'ok' | 'unauthorised',
) {
  const calls: InternalAxiosRequestConfig[] = []
  const adapter = (config: InternalAxiosRequestConfig) => {
    calls.push(config)
    const url = config.url ?? ''
    if (handler(url, calls) === 'unauthorised') {
      return Promise.reject(rejection(config, 401))
    }
    const body = url.includes('/auth/refresh') ? { accessToken: 'refreshed-token' } : { ok: true }
    return Promise.resolve(response(config, body, 200))
  }
  return { adapter, calls }
}

const urls = (calls: InternalAxiosRequestConfig[]) => calls.map((c) => c.url)

afterEach(() => {
  tokenStore.clear()
  vi.restoreAllMocks()
})

describe('api client 401 handling', () => {
  it('refreshes once and retries once, then succeeds', async () => {
    tokenStore.set('expired-token')
    const { adapter, calls } = scriptedAdapter((url, all) => {
      const attempts = all.filter((c) => c.url === '/users/me').length
      return url === '/users/me' && attempts === 1 ? 'unauthorised' : 'ok'
    })
    const { client } = createApiClient({ adapter })

    const result = await client.get('/users/me')

    expect(result.status).toBe(200)
    // Exactly three calls: the 401, one refresh, one retry. Not two refreshes, not a loop.
    expect(urls(calls)).toEqual(['/users/me', '/auth/refresh', '/users/me'])
    expect(tokenStore.get()).toBe('refreshed-token')
    expect(calls[2]?.headers.Authorization).toBe('Bearer refreshed-token')
  })

  it('does not retry a second time when the retried request also fails', async () => {
    tokenStore.set('expired-token')
    const { adapter, calls } = scriptedAdapter((url) => (url === '/users/me' ? 'unauthorised' : 'ok'))
    const { client } = createApiClient({ adapter })

    await expect(client.get('/users/me')).rejects.toBeInstanceOf(AxiosError)

    // The retry 401s too. Without the `retried` flag this is an infinite loop.
    expect(urls(calls)).toEqual(['/users/me', '/auth/refresh', '/users/me'])
  })

  it('never refreshes in response to a 401 from the refresh endpoint itself', async () => {
    const { adapter, calls } = scriptedAdapter(() => 'unauthorised')
    const onSessionExpired = vi.fn()
    const { client } = createApiClient({ adapter, onSessionExpired })

    await expect(client.post('/auth/refresh')).rejects.toBeInstanceOf(AxiosError)

    // One call only. Refreshing a failed refresh is the textbook infinite loop.
    expect(urls(calls)).toEqual(['/auth/refresh'])
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('shares one refresh between concurrent 401s', async () => {
    tokenStore.set('expired-token')
    const { adapter, calls } = scriptedAdapter((url, all) => {
      const attempts = all.filter((c) => c.url === url).length
      return url !== '/auth/refresh' && attempts === 1 ? 'unauthorised' : 'ok'
    })
    const { client } = createApiClient({ adapter })

    await Promise.all([client.get('/a'), client.get('/b'), client.get('/c')])

    // One refresh, not three. Each rotation revokes the previous token, so three
    // refreshes would revoke the chain and force a logout out of a routine expiry.
    expect(calls.filter((c) => c.url === '/auth/refresh')).toHaveLength(1)
    expect(calls).toHaveLength(7) // 3 failures + 1 refresh + 3 retries
  })

  it('clears the token and reports the session over when refreshing fails', async () => {
    tokenStore.set('expired-token')
    const { adapter } = scriptedAdapter(() => 'unauthorised')
    const onSessionExpired = vi.fn()
    const { client } = createApiClient({ adapter, onSessionExpired })

    await expect(client.get('/users/me')).rejects.toBeInstanceOf(AxiosError)

    expect(tokenStore.get()).toBeNull()
    expect(onSessionExpired).toHaveBeenCalledOnce()
  })

  it('makes one request when two callers refresh at once', async () => {
    const { adapter, calls } = scriptedAdapter(() => 'ok')
    const { refreshSession } = createApiClient({ adapter })

    // React StrictMode invokes effects twice in development, which is exactly how this
    // was found: two boot refreshes replayed a rotated token, the backend correctly read
    // that as a leak, and it revoked the whole chain -- logging the user straight out.
    const [first, second] = await Promise.all([refreshSession(), refreshSession()])

    expect(urls(calls)).toEqual(['/auth/refresh'])
    expect(first).toEqual(second)
  })

  it('allows a later refresh after an earlier one has settled', async () => {
    const { adapter, calls } = scriptedAdapter(() => 'ok')
    const { refreshSession } = createApiClient({ adapter })

    await refreshSession()
    await refreshSession()

    // Single-flight must not mean single-use: the guard releases once settled.
    expect(calls).toHaveLength(2)
  })

  it('sends no Authorization header when there is no token', async () => {
    const { adapter, calls } = scriptedAdapter(() => 'ok')
    const { client } = createApiClient({ adapter })

    await client.get('/health-check')

    expect(calls[0]?.headers.Authorization).toBeUndefined()
  })
})
