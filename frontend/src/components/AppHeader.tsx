import { Link } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { NotificationBell } from '../features/notifications/NotificationBell'
import { Button } from './ui/Button'

export function AppHeader({ section }: { section?: string }) {
  const { user, logout } = useAuth()

  return (
    <header className="flex items-center justify-between border-b border-line px-4 py-3">
      <Link to="/" className="flex items-baseline gap-2">
        <span className="font-mono text-[11px] font-medium tracking-[0.06em] text-accent uppercase">
          Knot
        </span>
        {section !== undefined && (
          <span className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
            {section}
          </span>
        )}
      </Link>
      <div className="flex items-center gap-3">
        <NotificationBell />
        <span className="hidden text-sm text-ink-muted sm:inline">{user?.name}</span>
        <Button variant="ghost" onClick={() => void logout()}>
          Sign out
        </Button>
      </div>
    </header>
  )
}
