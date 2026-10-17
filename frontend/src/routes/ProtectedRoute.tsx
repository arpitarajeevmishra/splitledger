import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  // 'checking' is the silent refresh on boot. Rendering a redirect here would bounce every
  // reload to the sign-in page for a moment before bouncing back, which is the exact bug
  // "token in memory" is accused of causing.
  if (status === 'checking') {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-surface">
        <p className="font-mono text-[11px] tracking-[0.06em] text-ink-dim uppercase">Loading</p>
      </div>
    )
  }

  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <>{children}</>
}
