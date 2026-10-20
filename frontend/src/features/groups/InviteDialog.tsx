import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { messageFor } from '../../api/problem'
import type { IssuedInvite } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Field } from '../../components/ui/Field'
import { useInviteToGroup } from './useGroups'
import { inviteSchema, type InviteValues } from './schemas'

/**
 * Two states in one dialog: address the invite, then hand over the link.
 *
 * <p>The second state exists because the server returns the raw token exactly once and
 * stores only its hash -- there is no endpoint that can show it again, and the listing
 * deliberately does not carry it. So the link is shown until the admin dismisses the
 * dialog, with a copy button, and closing it is final. Saying so plainly is better than
 * letting someone discover it by closing too early.
 *
 * <p>Email delivery is SL-902; until then passing the link on is a manual step, which is
 * exactly what this screen is for.
 */
export function InviteDialog({
  groupId,
  groupName,
  open,
  onClose,
}: {
  groupId: string
  groupName: string
  open: boolean
  onClose: () => void
}) {
  const invite = useInviteToGroup(groupId)
  const [issued, setIssued] = useState<IssuedInvite | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<InviteValues>({ resolver: zodResolver(inviteSchema) })

  function close() {
    setIssued(null)
    setFormError(null)
    setCopied(false)
    reset()
    onClose()
  }

  async function onSubmit(values: InviteValues) {
    setFormError(null)
    try {
      setIssued(await invite.mutateAsync(values.email))
    } catch (error) {
      setFormError(messageFor(error, 'Could not create that invite.'))
    }
  }

  async function copyLink() {
    if (issued === null) {
      return
    }
    try {
      await navigator.clipboard.writeText(issued.url)
      setCopied(true)
    } catch {
      // Clipboard access can be refused, and over plain HTTP it does not exist at all.
      // The link is on screen and selectable either way, so this is not an error worth
      // interrupting anyone over.
      setCopied(false)
    }
  }

  if (issued !== null) {
    return (
      <Dialog
        open={open}
        onClose={close}
        title="Invite created"
        description={`Send this link to ${issued.email}. It works once, and only for that address.`}
      >
        <div className="flex flex-col gap-4">
          <div className="rounded border border-line bg-surface-2 p-3">
            <p className="font-mono text-[11px] tracking-[0.04em] text-ink-dim uppercase">
              Invite link
            </p>
            <p className="mt-2 break-all font-mono text-xs text-ink">{issued.url}</p>
          </div>
          <p className="text-xs text-ink-dim">
            This is the only time the link is shown &mdash; the server keeps just a hash of it. If
            you close this before copying, revoke the invite and send a new one.
          </p>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => void copyLink()}>
              {copied ? 'Copied' : 'Copy link'}
            </Button>
            <Button onClick={close}>Done</Button>
          </div>
        </div>
      </Dialog>
    )
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title="Invite someone"
      description={`They join ${groupName} by accepting. You will get a link to pass on.`}
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
        <Field
          label="Email"
          type="email"
          autoComplete="off"
          placeholder="them@example.com"
          error={errors.email?.message}
          hint={
            <p className="text-xs text-ink-dim">They must sign in with this address to accept.</p>
          }
          {...register('email')}
        />
        {formError !== null && (
          <p
            role="alert"
            className="rounded border border-negative/40 bg-negative/10 px-3 py-2 text-xs text-negative"
          >
            {formError}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Creating…' : 'Create invite'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
