import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { authApi, type SelfProfile } from '../api/auth'
import { refreshSession, setSessionExpiredHandler } from '../api/client'
import { AuthContext, type AuthContextValue, type AuthStatus } from './AuthContext'
import { tokenStore } from './tokenStore'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('checking')
  const [user, setUser] = useState<SelfProfile | null>(null)
  const auth = useMemo(() => authApi(), [])

  const adopt = useCallback((accessToken: string, profile: SelfProfile) => {
    tokenStore.set(accessToken)
    setUser(profile)
    setStatus('authenticated')
  }, [])

  const forget = useCallback(() => {
    tokenStore.clear()
    setUser(null)
    setStatus('anonymous')
  }, [])

  useEffect(() => {
    setSessionExpiredHandler(forget)
    return () => setSessionExpiredHandler(null)
  }, [forget])

  // A reload leaves no access token in memory but the refresh cookie is still there, so
  // the session is recovered silently. This is what makes "token in memory only" viable
  // rather than a logout on every refresh (ADR-011).
  useEffect(() => {
    let cancelled = false
    // Through the client's single-flight refresh, not a direct POST. StrictMode invokes
    // this effect twice in development, and two refreshes would replay a rotated token --
    // which the backend correctly treats as a leak and answers by revoking the chain.
    void refreshSession().then((session) => {
      if (cancelled) return
      if (session === null) {
        setUser(null)
        setStatus('anonymous')
      } else {
        adopt(session.accessToken, session.user)
      }
    })
    return () => {
      cancelled = true
    }
  }, [adopt])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      login: async (email, password) => {
        const session = await auth.login(email, password)
        adopt(session.accessToken, session.user)
      },
      register: async (email, name, password) => {
        const session = await auth.register(email, name, password)
        adopt(session.accessToken, session.user)
      },
      logout: async () => {
        try {
          await auth.logout()
        } finally {
          // Clear locally even if the call failed: the user asked to be signed out, and
          // the refresh cookie is cleared by the server's Set-Cookie regardless.
          forget()
        }
      },
    }),
    [status, user, auth, adopt, forget],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
