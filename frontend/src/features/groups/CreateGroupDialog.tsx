import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router-dom'
import { messageFor } from '../../api/problem'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Field } from '../../components/ui/Field'
import { useCreateGroup } from './useGroups'
import { createGroupSchema, type CreateGroupValues } from './schemas'

export function CreateGroupDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const createGroup = useCreateGroup()
  const navigate = useNavigate()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<CreateGroupValues>({ resolver: zodResolver(createGroupSchema) })

  function close() {
    setFormError(null)
    reset()
    onClose()
  }

  async function onSubmit(values: CreateGroupValues) {
    setFormError(null)
    try {
      const group = await createGroup.mutateAsync(values.name)
      close()
      // Straight into the new group: it is empty, and the next thing anyone wants to do
      // is invite someone to it.
      navigate(`/groups/${group.id}`)
    } catch (error) {
      setFormError(messageFor(error))
    }
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title="New group"
      description="A group holds shared expenses. You can invite people once it exists."
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
        <Field
          label="Group name"
          placeholder="Flat 402"
          autoComplete="off"
          error={errors.name?.message}
          {...register('name')}
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
            {isSubmitting ? 'Creating…' : 'Create group'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
