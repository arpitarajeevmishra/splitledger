import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { AuthProvider } from './auth/AuthProvider'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      // On, because two people in a group is the premise of the product: without it the
      // only thing that ever refreshes a screen is a mutation made in that same tab, so
      // the other person's expenses never arrive and a tab left open shows yesterday's
      // balances until someone reloads. The 30s staleTime is what keeps it from being
      // chatty -- returning to a tab refetches only what is already stale.
      refetchOnWindowFocus: true,
      retry: false,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
