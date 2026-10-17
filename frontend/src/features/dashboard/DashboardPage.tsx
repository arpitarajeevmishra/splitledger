import { useAuth } from '../../auth/useAuth'
import { Button } from '../../components/ui/Button'
import { DemoBanner } from '../../components/DemoBanner'

/**
 * Deliberately sparse. The mockups show a populated dashboard, but groups, expenses and
 * balances do not exist until S2-S4 -- so this shows a real zero rather than invented
 * figures. Fabricated numbers in a ledger product are the one thing worth avoiding at any
 * stage.
 */
export function DashboardPage() {
  const { user, logout } = useAuth()

  return (
    <div className="flex min-h-dvh flex-col bg-surface">
      <DemoBanner />
      <header className="flex items-center justify-between border-b border-line px-4 py-3">
        <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-accent uppercase">
          Knot
        </p>
        <div className="flex items-center gap-3">
          <span className="text-sm text-ink-muted">{user?.name}</span>
          <Button variant="ghost" onClick={() => void logout()}>
            Sign out
          </Button>
        </div>
      </header>

      <main className="mx-auto w-full max-w-[1280px] flex-1 px-4 py-6">
        <section className="rounded-lg border border-line bg-surface-1 p-4">
          <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
            Net position
          </p>
          <p className="tabular mt-2 text-[32px] leading-[38px] font-semibold text-ink">
            &#8377;0.00
          </p>
          <p className="mt-1 text-sm text-ink-muted">Nothing owed either way.</p>
        </section>

        <section className="mt-6 rounded-lg border border-line bg-surface-1">
          <div className="border-b border-line px-4 py-3">
            <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
              Groups
            </p>
          </div>
          <div className="px-4 py-8 text-center">
            <p className="text-sm text-ink-muted">No groups yet.</p>
            <p className="mt-1 text-xs text-ink-dim">
              Creating groups and adding expenses arrives in the next sprints.
            </p>
          </div>
        </section>

        <p className="mt-6 text-xs text-ink-dim">
          Signed in as <span className="tabular text-ink-muted">{user?.email}</span>
        </p>
      </main>
    </div>
  )
}
