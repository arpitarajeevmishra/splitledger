import { useAuth } from '../../auth/useAuth'
import { AppHeader } from '../../components/AppHeader'
import { DemoBanner } from '../../components/DemoBanner'
import { GroupsPanel } from '../groups/GroupsPanel'

/**
 * Still deliberately sparse. Groups are real as of S2; the net position is not, because
 * expenses and balances arrive in S3-S4. So the figure below is a true zero for an
 * account that has posted nothing, not a placeholder standing in for a number the system
 * cannot yet compute. Fabricated figures in a ledger product are the one thing worth
 * avoiding at any stage.
 */
export function DashboardPage() {
  const { user } = useAuth()

  return (
    <div className="flex min-h-dvh flex-col bg-surface">
      <DemoBanner />
      <AppHeader />

      <main className="mx-auto w-full max-w-[1280px] flex-1 px-4 py-6">
        <section className="rounded-lg border border-line bg-surface-1 p-4">
          <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
            Net position
          </p>
          <p className="tabular mt-2 text-[32px] leading-[38px] font-semibold text-ink">
            &#8377;0.00
          </p>
          <p className="mt-1 text-sm text-ink-muted">
            Nothing owed either way &mdash; expenses arrive in the next sprint.
          </p>
        </section>

        <div className="mt-6">
          <GroupsPanel />
        </div>

        <p className="mt-6 text-xs text-ink-dim">
          Signed in as <span className="tabular text-ink-muted">{user?.email}</span>
        </p>
      </main>
    </div>
  )
}
