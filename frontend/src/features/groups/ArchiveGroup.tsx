import { useState } from 'react'
import { messageFor } from '../../api/problem'
import type { GroupDetail } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { useArchiveGroup, useUnarchiveGroup } from './useGroups'

/**
 * Putting a group away, and bringing it back.
 *
 * <p>Called archiving rather than deleting because that is what it does. The ledger is
 * immutable by design (ADR-003) and a group's rows are the evidence behind every balance
 * its members have already acted on, so a button labelled "delete" would either have to
 * orphan those postings or erase a record somebody settled money against. The dialog says
 * plainly that nothing is destroyed, so nobody presses it expecting one thing and gets
 * another.
 */
export function ArchiveGroup({ group }: { group: GroupDetail }) {
  const archive = useArchiveGroup()
  const unarchive = useUnarchiveGroup()
  const [confirming, setConfirming] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const busy = archive.isPending || unarchive.isPending

  async function run(action: { mutateAsync: (id: string) => Promise<unknown> }) {
    setFailure(null)
    try {
      await action.mutateAsync(group.id)
      setConfirming(false)
    } catch (error) {
      setFailure(messageFor(error, 'Could not do that.'))
    }
  }

  if (group.archivedAt !== null) {
    return (
      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={() => void run(unarchive)} disabled={busy}>
          {unarchive.isPending ? 'Restoring…' : 'Restore group'}
        </Button>
        {failure !== null && (
          <p role="alert" className="text-sm text-negative">
            {failure}
          </p>
        )}
      </div>
    )
  }

  return (
    <>
      <Button variant="ghost" onClick={() => setConfirming(true)} disabled={busy}>
        Archive group
      </Button>

      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Archive group"
        description="It leaves your groups list and stops taking new entries. Nothing is deleted."
      >
        <div className="flex flex-col gap-4">
          <p className="text-sm text-ink-muted">
            <span className="text-ink">{group.name}</span> and everything in it — expenses, payments
            and balances — stay exactly as they are. You can restore it at any time.
          </p>
          {failure !== null && (
            <p role="alert" className="text-sm text-negative">
              {failure}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={busy}>
              Cancel
            </Button>
            <Button onClick={() => void run(archive)} disabled={busy}>
              {archive.isPending ? 'Archiving…' : 'Archive group'}
            </Button>
          </div>
        </div>
      </Dialog>
    </>
  )
}
