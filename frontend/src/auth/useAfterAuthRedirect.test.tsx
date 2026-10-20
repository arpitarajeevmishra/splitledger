import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { useAfterAuthRedirect } from './useAfterAuthRedirect'

function Probe() {
  return <p data-testid="target">{useAfterAuthRedirect()}</p>
}

function renderWithState(state: unknown) {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
      <Routes>
        <Route path="/login" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  )
  return screen.getByTestId('target').textContent
}

describe('useAfterAuthRedirect', () => {
  it('returns where you were headed', () => {
    expect(renderWithState({ from: '/invite/tok-123' })).toBe('/invite/tok-123')
  })

  it('falls back to the dashboard with no state', () => {
    expect(renderWithState(undefined)).toBe('/')
  })

  it('refuses a protocol-relative path, which would be an open redirect', () => {
    // navigate('//evil.example') leaves the app entirely; the leading pair is the trick.
    expect(renderWithState({ from: '//evil.example' })).toBe('/')
  })

  // One render per test: two in the same test leave both in the DOM and getByTestId
  // then finds a pair.
  it('refuses an absolute URL', () => {
    expect(renderWithState({ from: 'https://evil.example' })).toBe('/')
  })

  it('refuses a `from` that is not a string', () => {
    expect(renderWithState({ from: 42 })).toBe('/')
  })

  it('refuses router state that is not an object', () => {
    expect(renderWithState('not-an-object')).toBe('/')
  })
})
