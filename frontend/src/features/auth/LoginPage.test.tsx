import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext'
import type { AppConfig } from '../../api/config'
import { LoginPage } from './LoginPage'

const { useAppConfig } = vi.hoisted(() => ({ useAppConfig: vi.fn() }))
vi.mock('../../api/config', () => ({ useAppConfig }))

const CONFIG: AppConfig = {
  registrationEnabled: true,
  upiIntentEnabled: false,
  demoMode: true,
  guestTourEnabled: false,
  razorpayKeyId: null,
}

function renderLogin(overrides: Partial<AuthContextValue> = {}) {
  const value: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn().mockResolvedValue(undefined),
    register: vi.fn(),
    logout: vi.fn(),
    exploreDemo: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
  // The page reads the server's flags now, to decide whether to offer the guest tour
  // (SL-904). retry: false so a failed config fetch settles immediately -- the page is
  // designed to fall back to the safest answer, which is not offering it.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthContext.Provider value={value}>
          <LoginPage />
        </AuthContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return value
}

describe('LoginPage', () => {
  beforeEach(() => {
    useAppConfig.mockReturnValue(CONFIG)
  })

  it('does not offer the demo on a deployment that has none', () => {
    renderLogin()

    // The flag is false while the config is loading and if the call fails, so the button
    // is absent unless the server has actually said it has demo data. Offering it
    // optimistically would mean a visitor clicking straight into a 404.
    expect(screen.queryByRole('button', { name: /Explore the demo/i })).not.toBeInTheDocument()
  })

  it('offers the demo where the server has one, and needs no credentials', async () => {
    useAppConfig.mockReturnValue({ ...CONFIG, guestTourEnabled: true })
    const value = renderLogin()

    await userEvent.click(screen.getByRole('button', { name: 'Explore the demo' }))

    // No email, no password, nothing typed: the whole point is getting in without an
    // account.
    expect(value.exploreDemo).toHaveBeenCalledOnce()
    expect(value.login).not.toHaveBeenCalled()
  })

  it('says so when the demo is unavailable rather than failing silently', async () => {
    useAppConfig.mockReturnValue({ ...CONFIG, guestTourEnabled: true })
    renderLogin({ exploreDemo: vi.fn().mockRejectedValue(new Error('nope')) })

    await userEvent.click(screen.getByRole('button', { name: 'Explore the demo' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The demo is not available')
  })

  it('does not let the demo and a sign-in run at once', async () => {
    useAppConfig.mockReturnValue({ ...CONFIG, guestTourEnabled: true })
    let release: (() => void) | undefined
    renderLogin({
      exploreDemo: vi.fn().mockImplementation(
        () =>
          new Promise<void>((resolve) => {
            release = resolve
          }),
      ),
    })

    await userEvent.click(screen.getByRole('button', { name: 'Explore the demo' }))

    // Both disabled while one is in flight: two sessions started from one page is a
    // confusing way to end up signed in as somebody you did not choose.
    expect(screen.getByRole('button', { name: 'Opening the demo…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()
    release?.()
  })

  it('will not submit an empty form and says why', async () => {
    const value = renderLogin()

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findAllByText('Required')).toHaveLength(2)
    expect(value.login).not.toHaveBeenCalled()
  })

  it('rejects a malformed email before calling the API', async () => {
    const value = renderLogin()

    await userEvent.type(screen.getByLabelText('Email'), 'not-an-email')
    await userEvent.type(screen.getByLabelText('Password'), 'password123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter a valid email')).toBeInTheDocument()
    expect(value.login).not.toHaveBeenCalled()
  })

  it('submits valid credentials', async () => {
    const value = renderLogin()

    await userEvent.type(screen.getByLabelText('Email'), 'member@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'password123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(value.login).toHaveBeenCalledWith('member@example.com', 'password123')
  })

  it('shows one message for bad credentials, revealing nothing about which part was wrong', async () => {
    const problem = { code: 'INVALID_CREDENTIALS' }
    const login = vi.fn().mockRejectedValue(
      new AxiosError('x', '401', undefined, null, {
        data: problem,
        status: 401,
        statusText: '',
        headers: {},
        config: {} as never,
      }),
    )
    renderLogin({ login })

    await userEvent.type(screen.getByLabelText('Email'), 'member@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    // Telling an unknown email apart from a wrong password would let anyone enumerate
    // which addresses have accounts.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That email and password do not match an account.',
    )
  })
})
