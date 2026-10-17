import { createContext } from 'react'
import type { SelfProfile } from '../api/auth'

export type AuthStatus = 'checking' | 'authenticated' | 'anonymous'

export type AuthContextValue = {
  status: AuthStatus
  user: SelfProfile | null
  login: (email: string, password: string) => Promise<void>
  register: (email: string, name: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

/** Split from the provider so the module exports only components (Fast Refresh). */
export const AuthContext = createContext<AuthContextValue | null>(null)
