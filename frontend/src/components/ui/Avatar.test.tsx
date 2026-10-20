import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Avatar } from './Avatar'

function initials(name: string) {
  const { container } = render(<Avatar name={name} />)
  return container.textContent
}

describe('Avatar', () => {
  it('takes the first and last initial', () => {
    expect(initials('Alice Ray')).toBe('AR')
  })

  it('skips the middle name rather than cramming three in', () => {
    expect(initials('Alice Mary Ray')).toBe('AR')
  })

  it('handles a single name', () => {
    expect(initials('Alice')).toBe('A')
  })

  it('does not crash on a blank name', () => {
    expect(initials('   ')).toBe('?')
  })

  it('is hidden from screen readers, since the name is rendered beside it', () => {
    render(<Avatar name="Alice Ray" />)
    expect(screen.queryByText('AR')).not.toBeNull()
    expect(screen.getByText('AR')).toHaveAttribute('aria-hidden', 'true')
  })
})
