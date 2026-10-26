import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppConfig } from './config'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('./client', () => ({ api: { get } }))

/**
 * What the client believes before the server has told it anything.
 *
 * <p>Every flag here gates something: registration, a payment method, a demo sign-in. The
 * fallback is not a placeholder, it is the answer the application gives while it does not
 * know -- and for a failed request, the answer it keeps giving. Each default has to be the
 * one that offers nothing.
 */
describe('useAppConfig', () => {
  let client: QueryClient

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }

  beforeEach(() => {
    get.mockReset()
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  })

  it('offers nothing while it is still asking', () => {
    get.mockReturnValue(new Promise(() => {}))
    const { result } = renderHook(() => useAppConfig(), { wrapper })

    expect(result.current.registrationEnabled).toBe(false)
    expect(result.current.upiIntentEnabled).toBe(false)
    expect(result.current.guestTourEnabled).toBe(false)
    expect(result.current.razorpayKeyId).toBeNull()
    // The one default that is true: the banner saying none of this is real money must
    // not disappear because a request failed (ADR-013).
    expect(result.current.demoMode).toBe(true)
  })

  it('still offers nothing when the request fails outright', async () => {
    get.mockRejectedValue(new Error('network'))
    const { result } = renderHook(() => useAppConfig(), { wrapper })

    await waitFor(() => expect(get).toHaveBeenCalled())

    // A failure to learn the flags must not open anything. Defaulting the guest tour on
    // would put a button on the page that signs nobody in.
    expect(result.current.guestTourEnabled).toBe(false)
    expect(result.current.registrationEnabled).toBe(false)
    expect(result.current.demoMode).toBe(true)
  })

  it('uses what the server actually said', async () => {
    get.mockResolvedValue({
      data: {
        registrationEnabled: true,
        upiIntentEnabled: false,
        demoMode: true,
        guestTourEnabled: true,
        razorpayKeyId: 'rzp_test_x',
      },
    })
    const { result } = renderHook(() => useAppConfig(), { wrapper })

    await waitFor(() => expect(result.current.guestTourEnabled).toBe(true))
    expect(result.current.registrationEnabled).toBe(true)
    expect(result.current.razorpayKeyId).toBe('rzp_test_x')
  })
})
