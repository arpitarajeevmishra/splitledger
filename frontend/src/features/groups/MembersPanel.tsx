import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { messageFor } from '../../api/problem'
import type { GroupDetail, GroupMember } from '../../api/types'
import { Avatar } from '../../components/ui/Avatar'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { formatDate } from '../../lib/format'
import { useRemoveMember } from './useGroups'

export function MembersPanel({ group, youId }: { group: GroupDetail; youId: string | undefined }) {
  const removeMember = useRemoveMember(group.id)
  const navigate = useNavigate()
  const [pendingRemoval, setPendingRemoval] = useState<GroupMember | null>(null)
  const [error, setError] = useState<string | null>(null)

  const youAreAdmin = group.yourRole === 'ADMIN'

  async function confirmRemoval() {
    if (pendingRemoval === null) {
      return
    }
    const leaving = pendingRemoval.userId === youId
    setError(null)
    try {
      await removeMember.mutateAsync(pendingRemoval.userId)
      setPendingRemoval(null)
      if (leaving) {
        // The group is invisible to you now -- staying on its page would show a 404.
        navigate('/')
      }
    } catch (removalError) {
      // Outstanding balance and last-admin both land here, and both are worth reading:
      // they explain a refusal the user cannot otherwise account for.
      setError(messageFor(removalError, 'Could not remove them.'))
    }
  }

  return (
    <section className="rounded-lg border border-line bg-surface-1">
      <div className="flex items-center justify-between border-b border-line px-4 py-3">
        <h2 className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
          Members
        </h2>
        <span className="font-mono text-[11px] text-ink-dim">{group.members.length}</span>
      </div>

      <ul>
        {group.members.map((member) => {
          const isYou = member.userId === youId
          return (
            <li
              key={member.userId}
              className="flex items-center gap-3 border-b border-line px-4 py-3 last:border-b-0"
            >
              <Avatar name={member.name} />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-ink">
                  {member.name}
                  {isYou && <span className="text-ink-dim"> (you)</span>}
                </span>
                <span className="mt-0.5 block font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
                  {member.role === 'ADMIN' ? 'Admin' : 'Member'} &middot; joined{' '}
                  {formatDate(member.joinedAt)}
                </span>
              </span>
              {/* Anyone may remove themselves; only an admin may remove anyone else. The
                  server enforces both -- this only decides what is worth showing. */}
              {(isYou || youAreAdmin) && (
                <Button variant="ghost" onClick={() => setPendingRemoval(member)}>
                  {isYou ? 'Leave' : 'Remove'}
                </Button>
              )}
            </li>
          )
        })}
      </ul>

      <Dialog
        open={pendingRemoval !== null}
        onClose={() => {
          setPendingRemoval(null)
          setError(null)
        }}
        title={pendingRemoval?.userId === youId ? 'Leave group' : 'Remove member'}
        description={
          pendingRemoval?.userId === youId
            ? `Leave ${group.name}? Your past expenses stay on the ledger, and you can be invited back.`
            : `Remove ${pendingRemoval?.name ?? ''} from ${group.name}? Their past expenses stay on the ledger.`
        }
      >
        {error !== null && (
          <p
            role="alert"
            className="mb-4 rounded border border-negative/40 bg-negative/10 px-3 py-2 text-xs text-negative"
          >
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button
            variant="ghost"
            onClick={() => {
              setPendingRemoval(null)
              setError(null)
            }}
          >
            Cancel
          </Button>
          <Button onClick={() => void confirmRemoval()} disabled={removeMember.isPending}>
            {pendingRemoval?.userId === youId ? 'Leave' : 'Remove'}
          </Button>
        </div>
      </Dialog>
    </section>
  )
}
