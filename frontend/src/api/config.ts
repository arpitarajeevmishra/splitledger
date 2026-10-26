import { useQuery } from '@tanstack/react-query'
import type { AxiosInstance } from 'axios'
import { api } from './client'

export type AppConfig = {
  registrationEnabled: boolean
  upiIntentEnabled: boolean
  demoMode: boolean
  /**
   * Whether this deployment has demo data to show a visitor without signing up.
   *
   * True exactly when the server has a demo seed configured, so the button cannot be
   * offered by a deployment that would 404 on it (SL-904).
   */
  guestTourEnabled: boolean
  /**
   * The public half of Razorpay's key pair, or null when this deployment has no keys.
   *
   * One field rather than a key plus a separate "enabled" flag: two values can disagree,
   * and the disagreement either offers a card payment that 502s or hides one that works.
   * Present means offerable, because the key is exactly what Checkout needs (SL-605).
   */
  razorpayKeyId: string | null
}

export function configApi(client: AxiosInstance = api) {
  return {
    get: () => client.get<AppConfig>('/config').then((r) => r.data),
  }
}

const config = configApi()

/**
 * What this deployment has switched on.
 *
 * <p>Read from the server rather than compiled in, so a method the backend would refuse
 * is never offered and a method it would accept is never hidden. The two drifting apart
 * is how a disabled payment path quietly becomes reachable.
 *
 * <p>Falls back to the safest answer while loading or if the call fails: registration
 * closed, UPI off, no card payments, demo banner shown. A failure to learn the flags must
 * not open anything.
 */
export function useAppConfig(): AppConfig {
  const { data } = useQuery<AppConfig>({
    queryKey: ['config'],
    queryFn: config.get,
    staleTime: 5 * 60_000,
    retry: false,
  })

  return (
    data ?? {
      registrationEnabled: false,
      upiIntentEnabled: false,
      demoMode: true,
      guestTourEnabled: false,
      razorpayKeyId: null,
    }
  )
}
