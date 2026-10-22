import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { cloneElement, type ReactElement } from 'react'
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

  const queryClient = newQueryClient()

  // Everything is rebuilt on each call, the component element included. React bails out
  // of re-rendering a subtree whose element is referentially identical, so reusing it
  // would make a test that re-renders to simulate data arriving quietly assert nothing --
  // it would pass against the very bug it was written for.
  const build = () => {
    const fresh = cloneElement(ui)
    const inner =
      options.path === undefined ? (
        fresh
      ) : (
        <Routes>
          <Route path={options.path} element={fresh} />
        </Routes>
      )
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[options.route ?? '/']}>
          <AuthContext.Provider value={auth}>{inner}</AuthContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  const view = render(build())
  return {
    ...view,
    /** Render again through the same providers, as the app does when a query resolves. */
    renderAgain: () => view.rerender(build()),
  }
}
