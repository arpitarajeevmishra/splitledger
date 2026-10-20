import { useLocation } from 'react-router-dom'

/**
 * Where to go once signed in: back where you were headed, or the dashboard.
 *
 * <p>`from` is set by ProtectedRoute and by the invite landing page, and it arrives as
 * router state, which anything that can push a history entry could set. It is validated
 * rather than trusted: a value like `//evil.example` is a protocol-relative URL, not an
 * internal route, and handing it to navigate() is an open redirect. Only a single-slash
 * path is accepted.
 */
export function useAfterAuthRedirect(): string {
  const location = useLocation()
  const state: unknown = location.state
  const from =
    typeof state === 'object' && state !== null && 'from' in state
      ? (state as { from: unknown }).from
      : undefined

  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//')) {
    return '/'
  }
  return from
}
