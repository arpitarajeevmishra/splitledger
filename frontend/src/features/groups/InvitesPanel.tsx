import { useState } from 'react'
import { messageFor } from '../../api/problem'
import type { InviteStatus } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { expiryLabel, formatDate } from '../../lib/format'
import { InviteDialog } from './InviteDialog'
import { useGroupInvites, useRevokeInvite } from './useGroups'

const STATUS_STYLE: Record<InviteStatus, string> = {
  PENDING: 'text-info',
  ACCEPTED: 'text-positive',
  DECLINED: 'text-ink-dim',
  EXPIRED: 'text-ink-dim',
  REVOKED: 'text-ink-dim',
}

/** Admin only: the server returns 403 for anyone else, so members never see this. */
export function InvitesPanel({ groupId, groupName }: { groupId: string; groupName: string }) {
  const { data: invites, isPending, isError, error } = useGroupInvites(groupId, true)
  const revoke = useRevokeInvite(groupId)
  const [inviting, setInviting] = useState(false)
  const [revokeError, setRevokeError] = useState<string | null>(null)

  async function revokeInvite(inviteId: string) {
    setRevokeError(null)
    try {
      await revoke.mutateAsync(inviteId)
    } catch (failure) {
      setRevokeError(messageFor(failure, 'Could not revoke that invite.'))
    }
  }

  return (
    <section className="rounded-lg border border-line bg-surface-1">
      <div className="flex items-center justify-between border-b border-line px-4 py-3">
        <h2 className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
          Invites
        </h2>
        <Button onClick={() => setInviting(true)}>Invite someone</Button>
      </div>

      {isPending && <p className="px-4 py-6 text-center text-sm text-ink-dim">Loading invites…</p>}

      {isError && (
        <p role="alert" className="px-4 py-6 text-center text-sm text-negative">
          {messageFor(error, 'Could not load invites.')}
        </p>
      )}

      {revokeError !== null && (
        <p role="alert" className="px-4 pt-4 text-xs text-negative">
          {revokeError}
        </p>
      )}

      {invites?.length === 0 && (
        <div className="px-4 py-6 text-center">
          <p className="text-sm text-ink-muted">Nobody invited yet.</p>
          <p className="mt-1 text-xs text-ink-dim">
            An invite is the only way into a group &mdash; there is no way to add someone without
            their say-so.
          </p>
        </div>
      )}

      {invites !== undefined && invites.length > 0 && (
        <ul>
          {invites.map((invite) => (
            <li
              key={invite.id}
              className="flex items-center gap-3 border-b border-line px-4 py-3 last:border-b-0"
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate font-mono text-xs text-ink">{invite.email}</span>
                <span className="mt-0.5 block font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
                  <span className={STATUS_STYLE[invite.status]}>{invite.status}</span>
                  {invite.status === 'PENDING'
                    ? ` · ${expiryLabel(invite.expiresAt)}`
                    : ` · sent ${formatDate(invite.createdAt)}`}
                </span>
              </span>
              {/* Only a pending invite can be revoked; the rest are history. */}
              {invite.status === 'PENDING' && (
                <Button
                  variant="ghost"
                  disabled={revoke.isPending}
                  onClick={() => void revokeInvite(invite.id)}
                >
                  Revoke
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      <InviteDialog
        groupId={groupId}
        groupName={groupName}
        open={inviting}
        onClose={() => setInviting(false)}
      />
    </section>
  )
}
