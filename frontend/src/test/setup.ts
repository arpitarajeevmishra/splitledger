import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Vitest runs without globals here, so Testing Library's own auto-cleanup never registers
// and the DOM accumulates between tests -- which surfaces as "found multiple elements"
// in the second test that renders the same component.
afterEach(() => {
  cleanup()
})
