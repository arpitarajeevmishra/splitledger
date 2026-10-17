import axios, {
  AxiosError,
  type AxiosAdapter,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios'
import { tokenStore } from '../auth/tokenStore'
import type { AuthResponse } from './types'

/** The one endpoint a 401 must never trigger a refresh for -- that is the loop. */
const REFRESH_PATH = '/auth/refresh'

type RetriableConfig = InternalAxiosRequestConfig & { retried?: boolean }

export type ApiClientOptions = {
  /** Injectable so the interceptor can be tested without a network or a server. */
  adapter?: AxiosAdapter
  /** Called when refreshing fails, i.e. the session is genuinely over. */
  onSessionExpired?: () => void
}

export type ApiClient = {
  client: AxiosInstance
  /**
   * The *only* way to refresh. Single-flight: concurrent callers share one request.
   *
   * This is deliberately the client's property rather than each caller's discipline.
   * The backend revokes the entire token chain when a rotated token is replayed, which
   * is correct and security-critical -- so two refreshes racing is not a wasted request,
   * it is a forced logout. React StrictMode double-invokes effects precisely to surface
   * non-idempotent work like this, and it did.
   */
  refreshSession: () => Promise<AuthResponse | null>
}

/**
 * Relative base URL, deliberately not configurable (finding C13): one build artefact runs
 * in every environment, and the refresh cookie's SameSite=Strict only works because the
 * SPA and the API share an origin.
 */
export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const client = axios.create({
    baseURL: '/api/v1',
    withCredentials: true,
    timeout: 10_000,
    ...(options.adapter ? { adapter: options.adapter } : {}),
  })

  client.interceptors.request.use((config) => {
    const token = tokenStore.get()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })

  let refreshing: Promise<AuthResponse | null> | null = null

  function refreshSession(): Promise<AuthResponse | null> {
    refreshing ??= client
      .post<AuthResponse>(REFRESH_PATH)
      .then((response) => {
        tokenStore.set(response.data.accessToken)
        return response.data
      })
      .catch(() => {
        tokenStore.clear()
        options.onSessionExpired?.()
        return null
      })
      .finally(() => {
        refreshing = null
      })
    return refreshing
  }

  client.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const config = error.config as RetriableConfig | undefined

      const shouldRefresh =
        error.response?.status === 401 &&
        config !== undefined &&
        // Already retried once: a second 401 means the new token is not the problem.
        config.retried !== true &&
        // A 401 from the refresh endpoint itself means the session is over, not that it
        // needs refreshing. Retrying here is precisely the infinite loop.
        !config.url?.includes(REFRESH_PATH)

      if (!shouldRefresh) {
        return Promise.reject(error)
      }

      const session = await refreshSession()
      if (session === null) {
        return Promise.reject(error)
      }

      config.retried = true
      config.headers.Authorization = `Bearer ${session.accessToken}`
      return client.request(config)
    },
  )

  return { client, refreshSession }
}

let sessionExpiredHandler: (() => void) | null = null

/** Lets the auth provider react to a refresh failure without owning the client. */
export function setSessionExpiredHandler(handler: (() => void) | null): void {
  sessionExpiredHandler = handler
}

export const { client: api, refreshSession } = createApiClient({
  onSessionExpired: () => sessionExpiredHandler?.(),
})
