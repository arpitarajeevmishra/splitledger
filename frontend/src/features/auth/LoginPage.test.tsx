import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AxiosError } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '../../auth/AuthContext'
import { LoginPage } from './LoginPage'

function renderLogin(overrides: Partial<AuthContextValue> = {}) {
  const value: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn().mockResolvedValue(undefined),
    register: vi.fn(),
    logout: vi.fn(),
    ...overrides,
  }
  render(
    <MemoryRouter>
      <AuthContext.Provider value={value}>
        <LoginPage />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
  return value
}

describe('LoginPage', () => {
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

  it('shows one message for bad credentials, revealing nothing about which part was wrong',
    async () => {
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
