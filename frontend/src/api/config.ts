import { useQuery } from '@tanstack/react-query'
import type { AxiosInstance } from 'axios'
import { api } from './client'

export type AppConfig = {
  registrationEnabled: boolean
  upiIntentEnabled: boolean
  demoMode: boolean
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
 * closed, UPI off, demo banner shown. A failure to learn the flags must not open
 * anything.
 */
export function useAppConfig(): AppConfig {
  const { data } = useQuery<AppConfig>({
    queryKey: ['config'],
    queryFn: config.get,
    staleTime: 5 * 60_000,
    retry: false,
  })

  return data ?? { registrationEnabled: false, upiIntentEnabled: false, demoMode: true }
}
