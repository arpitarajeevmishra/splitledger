import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { useAppConfig } from '../../api/config'
import { messageFor } from '../../api/problem'
import { Button } from '../../components/ui/Button'
import { Field } from '../../components/ui/Field'
import { useAfterAuthRedirect } from '../../auth/useAfterAuthRedirect'
import { useAuth } from '../../auth/useAuth'
import { loginSchema, type LoginValues } from './schemas'
import { AuthLayout } from './AuthLayout'

export function LoginPage() {
  const { login, exploreDemo } = useAuth()
  const { guestTourEnabled } = useAppConfig()
  const navigate = useNavigate()
  const [exploring, setExploring] = useState(false)
  // Following an invite link lands here; signing in has to go back to it.
  const redirectTo = useAfterAuthRedirect()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema) })

  async function onSubmit(values: LoginValues) {
    setFormError(null)
    try {
      await login(values.email, values.password)
      navigate(redirectTo, { replace: true })
    } catch (error) {
      // Deliberately the same message for an unknown email and a wrong password: telling
      // them apart would let anyone enumerate which addresses have accounts.
      setFormError(messageFor(error))
    }
  }

  async function onExplore() {
    setFormError(null)
    setExploring(true)
    try {
      await exploreDemo()
      // Straight to the groups list rather than to redirectTo: somebody who followed an
      // invite link and then chose to explore instead is not accepting that invitation,
      // and dropping them on it as a different account would be baffling.
      navigate('/', { replace: true })
    } catch (error) {
      setFormError(messageFor(error, 'The demo is not available just now.'))
    } finally {
      setExploring(false)
    }
  }

  return (
    <AuthLayout
      title="Sign in"
      subtitle="Shared expenses, settled precisely."
      footer={
        <>
          No account?{' '}
          <Link to="/register" className="text-accent hover:underline">
            Create one
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
        <Field
          label="Email"
          type="email"
          autoComplete="email"
          autoFocus
          error={errors.email?.message}
          {...register('email')}
        />
        <Field
          label="Password"
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password')}
        />
        {formError !== null && (
          <p
            role="alert"
            className="rounded border border-negative/40 bg-negative/10 px-3 py-2 text-xs text-negative"
          >
            {formError}
          </p>
        )}
        <Button type="submit" disabled={isSubmitting || exploring}>
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>

      {/* Offered only where the server has demo data, so the button cannot 404. Below the
          form and visually quieter: somebody who came here to sign in should not have to
          read past an invitation to do something else. */}
      {guestTourEnabled && (
        <div className="mt-6 border-t border-line pt-5 text-center">
          <Button
            type="button"
            variant="ghost"
            disabled={isSubmitting || exploring}
            onClick={() => void onExplore()}
          >
            {exploring ? 'Opening the demo…' : 'Explore the demo'}
          </Button>
          <p className="mt-2 text-xs text-ink-dim">
            No signup. You will be signed in to a shared example group.
          </p>
        </div>
      )}
    </AuthLayout>
  )
}
