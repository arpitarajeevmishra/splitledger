import type { AxiosInstance } from 'axios'
import { api } from './client'
import type { AuthResponse, SelfProfile } from './types'

export type { ApiProblem, AuthResponse, SelfProfile } from './types'

export function authApi(client: AxiosInstance = api) {
  return {
    register: (email: string, name: string, password: string) =>
      client.post<AuthResponse>('/auth/register', { email, name, password }).then((r) => r.data),

    login: (email: string, password: string) =>
      client.post<AuthResponse>('/auth/login', { email, password }).then((r) => r.data),

    /**
     * No credentials: the server signs the visitor in as the seeded demo account (SL-904).
     * POST rather than GET because it starts a session and sets a cookie.
     */
    exploreDemo: () => client.post<AuthResponse>('/auth/demo').then((r) => r.data),

    // Refreshing goes through the client's single-flight refreshSession(), never here:
    // two concurrent refreshes make the backend revoke the whole chain.

    logout: () => client.post<void>('/auth/logout').then(() => undefined),

    me: () => client.get<SelfProfile>('/users/me').then((r) => r.data),

    updateProfile: (patch: { name?: string; upiId?: string }) =>
      client.patch<SelfProfile>('/users/me', patch).then((r) => r.data),
  }
}
