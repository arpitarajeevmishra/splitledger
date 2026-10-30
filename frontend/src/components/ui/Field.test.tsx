import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Field } from './Field'

/**
 * The reveal toggle on password fields.
 *
 * Mostly about the two ways this goes wrong quietly: a button inside a form that submits
 * it instead of toggling, and a control a screen reader cannot name or whose name never
 * changes, so pressing it says nothing happened.
 */
describe('Field', () => {
  it('starts hidden and reveals on request', async () => {
    render(<Field label="Password" name="password" type="password" />)
    const input = screen.getByLabelText('Password')

    expect(input).toHaveAttribute('type', 'password')

    await userEvent.click(screen.getByRole('button', { name: 'Show password' }))
    expect(input).toHaveAttribute('type', 'text')

    // And back, by the same control -- which now names the thing it will do, not the
    // state it is in: pressing it is what a screen reader reads out.
    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(input).toHaveAttribute('type', 'password')
  })

  it('keeps whatever was typed when the type flips', async () => {
    render(<Field label="Password" name="password" type="password" />)
    const input = screen.getByLabelText('Password')

    await userEvent.type(input, 'correct horse')
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }))

    // Queried again rather than reusing the reference above: an implementation that
    // remounts the input to change its type would pass against the old, detached node
    // while the field on screen sat empty.
    expect(screen.getByLabelText('Password')).toHaveValue('correct horse')
  })

  it('does not submit the form it sits in', async () => {
    const onSubmit = vi.fn((event: React.FormEvent) => event.preventDefault())
    render(
      <form onSubmit={onSubmit}>
        <Field label="Password" name="password" type="password" />
        <button type="submit">Sign in</button>
      </form>,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Show password' }))

    // A button with no explicit type is a submit button. Revealing a password must not
    // sign you in, and must not sign you in with a half-typed one.
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('reaches the toggle from the field by keyboard', async () => {
    render(<Field label="Password" name="password" type="password" />)

    screen.getByLabelText('Password').focus()
    await userEvent.tab()

    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Show password' }))
  })

  it('offers nothing to reveal on an ordinary field', () => {
    render(<Field label="Email" name="email" type="email" />)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email')
  })
})
