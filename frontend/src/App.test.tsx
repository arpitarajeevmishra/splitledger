import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./api/client', () => ({
  api: { get: vi.fn().mockResolvedValue({ data: { status: 'UP' } }) },
}))

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
}

describe('App', () => {
  it('renders the application heading', () => {
    renderApp()
    expect(screen.getByRole('heading', { name: 'SplitLedger' })).toBeInTheDocument()
  })

  it('shows the API health status once the request resolves', async () => {
    renderApp()
    expect(await screen.findByText(/API reports: UP/)).toBeInTheDocument()
  })
})
