import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { invitesApi } from '../../api/groups'
import { messageFor } from '../../api/problem'
import type { InvitePreview } from '../../api/types'
import { useAuth } from '../../auth/useAuth'
import { Button } from '../../components/ui/Button'
import { DemoBanner } from '../../components/DemoBanner'
import { formatDate } from '../../lib/format'

const api = invitesApi()

/**
 * The invite landing page, reached from the link and addressed by token.
 *
 * <p>Public on purpose: it says which group the invitation is for before asking anyone to
 * sign in, because "sign in to find out what this is" is a bad trade for the recipient.
 * Accepting is not public -- it needs a session whose email matches the invited address,
 * so someone who merely came across the link cannot join.
 */
export function InvitePage() {
  const { token } = useParams<{ token: string }>()
  const { status, user } = useAuth()
  const navigate = useNavigate()
  const [actionError, setActionError] = useState<string | null>(null)
  const [declined, setDeclined] = useState(false)

  const preview = useQuery<InvitePreview>({
    queryKey: ['invite', token],
    queryFn: () => api.preview(token ?? ''),
    enabled: token !== undefined,
    retry: false,
  })

  const accept = useMutation({ mutationFn: () => api.accept(token ?? '') })
  const decline = useMutation({ mutationFn: () => api.decline(token ?? '') })

  async function onAccept() {
    setActionError(null)
    try {
      const { groupId } = await accept.mutateAsync()
      navigate(`/groups/${groupId}`, { replace: true })
    } catch (error) {
      setActionError(messageFor(error, 'Could not accept this invite.'))
    }
  }

  async function onDecline() {
    setActionError(null)
    try {
      await decline.mutateAsync()
      setDeclined(true)
    } catch (error) {
      setActionError(messageFor(error, 'Could not decline this invite.'))
    }
  }

  const invited = preview.data
  // Known before signing in, so the page can say which address to use rather than letting
  // someone sign in with the wrong one and be refused.
  const wrongAccount =
    invited !== undefined &&
    user !== null &&
    user.email.trim().toLowerCase() !== invited.email.trim().toLowerCase()

  return (
    <div className="flex min-h-dvh flex-col bg-surface">
      <DemoBanner />
      <main className="mx-auto flex w-full max-w-[420px] flex-1 flex-col justify-center px-4 py-10">
        <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-accent uppercase">
          Knot
        </p>

        {preview.isPending && (
          <p className="mt-6 text-sm text-ink-dim">Checking the invite…</p>
        )}

        {preview.isError && (
          <div className="mt-6">
            <h1 className="text-[22px] leading-[28px] font-semibold text-ink">
              This link is not valid
            </h1>
            <p role="alert" className="mt-2 text-sm text-ink-muted">
              {messageFor(preview.error, 'This invite link is no longer valid.')}
            </p>
            <p className="mt-1 text-xs text-ink-dim">
              Links expire, and can be revoked or already used. Ask whoever invited you for a new
              one.
            </p>
            <Link to="/" className="mt-6 inline-block text-sm text-accent hover:underline">
              Go to Knot
            </Link>
          </div>
        )}

        {invited !== undefined && declined && (
          <div className="mt-6">
            <h1 className="text-[22px] leading-[28px] font-semibold text-ink">Invite declined</h1>
            <p className="mt-2 text-sm text-ink-muted">
              You have not joined {invited.groupName}, and the link no longer works.
            </p>
          </div>
        )}

        {invited !== undefined && !declined && (
          <div className="mt-6">
            <h1 className="text-[22px] leading-[28px] font-semibold text-ink">
              You are invited to {invited.groupName}
            </h1>
            <p className="mt-2 text-sm text-ink-muted">
              Addressed to <span className="font-mono text-ink">{invited.email}</span>
            </p>
            <p className="mt-1 text-xs text-ink-dim">Valid until {formatDate(invited.expiresAt)}</p>

            {status === 'anonymous' && (
              <div className="mt-6 flex flex-col gap-3">
                <p className="text-sm text-ink-muted">
                  Sign in with that address to accept, or create an account with it.
                </p>
                <div className="flex gap-2">
                  <Link to="/login" state={{ from: `/invite/${token ?? ''}` }}>
                    <Button>Sign in</Button>
                  </Link>
                  <Link to="/register" state={{ from: `/invite/${token ?? ''}` }}>
                    <Button variant="ghost">Create account</Button>
                  </Link>
                </div>
              </div>
            )}

            {status === 'authenticated' && wrongAccount && (
              <p
                role="alert"
                className="mt-6 rounded border border-line bg-surface-1 px-3 py-2 text-sm text-ink-muted"
              >
                You are signed in as <span className="font-mono text-ink">{user?.email}</span>, but
                this invite was sent to someone else. Sign in with the invited address to accept it.
              </p>
            )}

            {status === 'authenticated' && !wrongAccount && (
              <div className="mt-6 flex gap-2">
                <Button onClick={() => void onAccept()} disabled={accept.isPending}>
                  {accept.isPending ? 'Joining…' : 'Accept and join'}
                </Button>
                <Button
                  variant="ghost"
                  onClick={() => void onDecline()}
                  disabled={decline.isPending}
                >
                  Decline
                </Button>
              </div>
            )}

            {actionError !== null && (
              <p role="alert" className="mt-4 text-sm text-negative">
                {actionError}
              </p>
            )}
          </div>
        )}
      </main>
    </div>
  )
}
