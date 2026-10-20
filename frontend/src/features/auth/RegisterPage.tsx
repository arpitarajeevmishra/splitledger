import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { messageFor, problemOf } from '../../api/problem'
import { Button } from '../../components/ui/Button'
import { Field } from '../../components/ui/Field'
import { useAfterAuthRedirect } from '../../auth/useAfterAuthRedirect'
import { useAuth } from '../../auth/useAuth'
import { registerSchema, type RegisterValues } from './schemas'
import { AuthLayout } from './AuthLayout'

export function RegisterPage() {
  const { register: createAccount } = useAuth()
  const navigate = useNavigate()
  // Following an invite link lands here; signing in has to go back to it.
  const redirectTo = useAfterAuthRedirect()
  const [formError, setFormError] = useState<string | null>(null)
  const [closed, setClosed] = useState(false)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({ resolver: zodResolver(registerSchema) })

  async function onSubmit(values: RegisterValues) {
    setFormError(null)
    try {
      await createAccount(values.email, values.name, values.password)
      navigate(redirectTo, { replace: true })
    } catch (error) {
      // Registration is flag-gated until email verification ships (ADR-013, SL-901), so a
      // 403 here is expected configuration rather than a fault. Say so plainly instead of
      // showing a generic failure.
      if (problemOf(error)?.code === 'REGISTRATION_DISABLED') {
        setClosed(true)
        return
      }
      setFormError(messageFor(error))
    }
  }

  return (
    <AuthLayout
      title="Create account"
      subtitle="Track shared expenses on a ledger that cannot drift."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="text-accent hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      {closed ? (
        <p role="alert" className="text-sm text-ink-muted">
          Registration is closed at the moment. It opens once email verification is in place.
        </p>
      ) : (
        <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
          <Field
            label="Name"
            autoComplete="name"
            autoFocus
            error={errors.name?.message}
            {...register('name')}
          />
          <Field
            label="Email"
            type="email"
            autoComplete="email"
            error={errors.email?.message}
            {...register('email')}
          />
          <Field
            label="Password"
            type="password"
            autoComplete="new-password"
            error={errors.password?.message}
            hint={<p className="text-xs text-ink-dim">8 to 72 characters.</p>}
            {...register('password')}
          />
          {formError !== null && (
            <p role="alert" className="rounded border border-negative/40 bg-negative/10 px-3 py-2 text-xs text-negative">
              {formError}
            </p>
          )}
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Creating…' : 'Create account'}
          </Button>
        </form>
      )}
    </AuthLayout>
  )
}
