import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext, type AuthContextValue } from '../auth/AuthContext'
import type { SelfProfile } from '../api/types'

export const SOMEONE: SelfProfile = {
  id: 'user-1',
  email: 'alice@example.com',
  name: 'Alice Ray',
  upiId: null,
}

/**
 * retry: false matters -- the default retries would make a test that asserts an error
 * state wait for three failed attempts before rendering it, and it would time out rather
 * than fail with a useful message.
 */
function newQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

export function renderWithProviders(
  ui: ReactElement,
  options: {
    auth?: Partial<AuthContextValue>
    route?: string
    path?: string
  } = {},
) {
  const auth: AuthContextValue = {
    status: 'authenticated',
    user: SOMEONE,
    login: async () => {},
    register: async () => {},
    logout: async () => {},
    ...options.auth,
  }

  const wrapped: ReactNode =
    options.path === undefined ? (
      ui
    ) : (
      <Routes>
        <Route path={options.path} element={ui} />
      </Routes>
    )

  return render(
    <QueryClientProvider client={newQueryClient()}>
      <MemoryRouter initialEntries={[options.route ?? '/']}>
        <AuthContext.Provider value={auth}>{wrapped}</AuthContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
