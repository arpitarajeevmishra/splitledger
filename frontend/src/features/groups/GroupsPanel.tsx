import { useState } from 'react'
import { Link } from 'react-router-dom'
import { messageFor } from '../../api/problem'
import { Button } from '../../components/ui/Button'
import { CreateGroupDialog } from './CreateGroupDialog'
import { useArchivedGroups, useGroups } from './useGroups'

export function GroupsPanel() {
  const { data: groups, isPending, isError, error } = useGroups()
  const [showingArchived, setShowingArchived] = useState(false)
  // Fetched only when asked for: most visits never open the archive.
  const { data: archived } = useArchivedGroups(showingArchived)
  const [creating, setCreating] = useState(false)

  return (
    <section className="rounded-lg border border-line bg-surface-1">
      <div className="flex items-center justify-between border-b border-line px-4 py-3">
        <h2 className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
          Groups
        </h2>
        <Button onClick={() => setCreating(true)}>New group</Button>
      </div>

      {isPending && <p className="px-4 py-6 text-center text-sm text-ink-dim">Loading groups…</p>}

      {isError && (
        <p role="alert" className="px-4 py-6 text-center text-sm text-negative">
          {messageFor(error, 'Could not load your groups.')}
        </p>
      )}

      {groups?.length === 0 && (
        <div className="px-4 py-6 text-center">
          <p className="text-sm text-ink-muted">No groups yet.</p>
          <p className="mt-1 text-xs text-ink-dim">
            Create one, then invite the people you share expenses with.
          </p>
        </div>
      )}

      {groups !== undefined && groups.length > 0 && (
        <ul>
          {groups.map((group) => (
            <li key={group.id} className="border-b border-line last:border-b-0">
              <Link
                to={`/groups/${group.id}`}
                className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-surface-2"
              >
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium text-ink">{group.name}</span>
                  <span className="mt-0.5 block font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
                    {group.memberCount} {group.memberCount === 1 ? 'member' : 'members'}
                    {group.yourRole === 'ADMIN' && ' · admin'}
                  </span>
                </span>
                {/* No balance here: balances arrive in S4, and a zero shown now would be
                    an invented figure rather than a computed one. */}
                <span aria-hidden="true" className="text-ink-dim">
                  &rsaquo;
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}

      <div className="border-t border-line px-4 py-3">
        <button
          type="button"
          onClick={() => setShowingArchived((showing) => !showing)}
          className="font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase hover:text-ink"
        >
          {showingArchived ? 'Hide archived' : 'Show archived'}
        </button>

        {showingArchived && (
          <ul className="mt-2">
            {archived === undefined && <li className="text-sm text-ink-dim">Loading…</li>}
            {archived !== undefined && archived.length === 0 && (
              <li className="text-sm text-ink-dim">Nothing archived.</li>
            )}
            {archived?.map((group) => (
              <li key={group.id}>
                {/* Still a link: an archived group keeps every number it ever had, and
                    going in is how you restore it. */}
                <Link
                  to={`/groups/${group.id}`}
                  className="flex items-center justify-between gap-3 py-2 hover:text-ink"
                >
                  <span className="min-w-0 truncate text-sm text-ink-muted">{group.name}</span>
                  <span className="font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
                    archived
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>

      <CreateGroupDialog open={creating} onClose={() => setCreating(false)} />
    </section>
  )
}
