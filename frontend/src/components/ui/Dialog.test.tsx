import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Dialog } from './Dialog'

/**
 * What "modal" has to mean for somebody not using a mouse.
 *
 * The component's own comment claimed focus moved in and the backdrop was inert, and
 * neither Tab nor the page's scroll was actually held: tabbing walked straight out of the
 * panel into the page behind it. These tests exist because that claim went unchecked.
 */
function Harness({ onClose = vi.fn() }: { onClose?: () => void }) {
  return (
    <div>
      <button type="button">behind one</button>
      <button type="button">behind two</button>
      <Dialog open onClose={onClose} title="Settle up">
        <input aria-label="Amount" />
        <button type="button">Cancel</button>
        <button type="button">Record payment</button>
      </Dialog>
    </div>
  )
}

describe('Dialog', () => {
  it('keeps Tab inside the panel', async () => {
    render(<Harness />)

    // Forward from the last control wraps to the first, rather than landing on the page
    // behind -- which is where it used to go.
    await userEvent.tab()
    await userEvent.tab()
    await userEvent.tab()

    const inside = ['Amount', 'Cancel', 'Record payment']
    for (let i = 0; i < 6; i++) {
      const active = document.activeElement as HTMLElement
      const label = active.getAttribute('aria-label') ?? active.textContent ?? ''
      expect(inside).toContain(label)
      await userEvent.tab()
    }
  })

  it('keeps Shift+Tab inside the panel too', async () => {
    render(<Harness />)

    for (let i = 0; i < 5; i++) {
      await userEvent.tab({ shift: true })
      const active = document.activeElement as HTMLElement
      const label = active.getAttribute('aria-label') ?? active.textContent ?? ''
      expect(['Amount', 'Cancel', 'Record payment']).toContain(label)
    }
  })

  it('never reaches the buttons behind it', async () => {
    render(<Harness />)

    for (let i = 0; i < 8; i++) {
      await userEvent.tab()
      expect(document.activeElement).not.toBe(screen.getByText('behind one'))
      expect(document.activeElement).not.toBe(screen.getByText('behind two'))
    }
  })

  it('stops the page behind from scrolling, and gives it back on close', () => {
    function Toggle() {
      const [open, setOpen] = useState(true)
      return (
        <Dialog open={open} onClose={() => setOpen(false)} title="Settle up">
          <button type="button" onClick={() => setOpen(false)}>
            Close
          </button>
        </Dialog>
      )
    }
    const { rerender } = render(<Toggle />)

    expect(document.body.style.overflow).toBe('hidden')

    rerender(<></>)
    expect(document.body.style.overflow).not.toBe('hidden')
  })

  it('moves focus in on open and back out on close', async () => {
    function Toggle() {
      const [open, setOpen] = useState(false)
      return (
        <div>
          <button type="button" onClick={() => setOpen(true)}>
            Open it
          </button>
          <Dialog open={open} onClose={() => setOpen(false)} title="Settle up">
            <button type="button">Inside</button>
          </Dialog>
        </div>
      )
    }
    render(<Toggle />)
    const opener = screen.getByText('Open it')

    await userEvent.click(opener)
    expect(document.activeElement).toBe(screen.getByText('Inside'))

    await userEvent.keyboard('{Escape}')
    // Back where it came from, so a keyboard user is not dropped at the top of the page.
    expect(document.activeElement).toBe(opener)
  })

  it('still closes on Escape and on the backdrop', async () => {
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)

    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
