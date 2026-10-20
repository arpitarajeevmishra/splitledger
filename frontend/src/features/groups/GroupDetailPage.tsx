import { Link, useParams } from 'react-router-dom'
import { messageFor } from '../../api/problem'
import { useAuth } from '../../auth/useAuth'
import { AppHeader } from '../../components/AppHeader'
import { DemoBanner } from '../../components/DemoBanner'
import { formatDate } from '../../lib/format'
import { InvitesPanel } from './InvitesPanel'
import { MembersPanel } from './MembersPanel'
import { useGroup } from './useGroups'

export function GroupDetailPage() {
  const { groupId } = useParams<{ groupId: string }>()
  const { user } = useAuth()
  const { data: group, isPending, isError, error } = useGroup(groupId ?? '')

  return (
    <div className="flex min-h-dvh flex-col bg-surface">
      <DemoBanner />
      <AppHeader section="Groups" />

      <main className="mx-auto w-full max-w-[1280px] flex-1 px-4 py-6">
        <Link
          to="/"
          className="font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase hover:text-ink"
        >
          &lsaquo; All groups
        </Link>

        {isPending && <p className="mt-6 text-sm text-ink-dim">Loading group…</p>}

        {isError && (
          <p role="alert" className="mt-6 text-sm text-negative">
            {/* A group you are not in returns 404, not 403, so "no such group" and "not
                yours" read identically here -- which is the server's intent. */}
            {messageFor(error, 'That group does not exist, or you are not a member of it.')}
          </p>
        )}

        {group !== undefined && (
          <>
            <section className="mt-4 rounded-lg border border-line bg-surface-1 p-4">
              <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
                {group.members.length} {group.members.length === 1 ? 'member' : 'members'}
                {' · '}
                {group.currency}
                {' · created '}
                {formatDate(group.createdAt)}
              </p>
              <h1 className="mt-2 text-[28px] leading-[34px] font-semibold text-ink">
                {group.name}
              </h1>
              <p className="mt-3 text-sm text-ink-muted">
                Expenses and balances arrive in the next sprint. For now a group is its people.
              </p>
            </section>

            <div className="mt-6 grid gap-6 lg:grid-cols-2">
              <MembersPanel group={group} youId={user?.id} />
              {/* Listing invites is an admin action on the server; asking as a member
                  would be a 403, so a member is not shown the panel at all. */}
              {group.yourRole === 'ADMIN' && (
                <InvitesPanel groupId={group.id} groupName={group.name} />
              )}
            </div>
          </>
        )}
      </main>
    </div>
  )
}
