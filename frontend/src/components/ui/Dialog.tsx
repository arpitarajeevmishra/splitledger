import { useEffect, useRef, type ReactNode } from 'react'

/**
 * A modal dialog.
 *
 * <p>Hand-rolled rather than using the native &lt;dialog&gt; element: jsdom's support for
 * showModal() is partial, so the component tests would be testing the polyfill gap rather
 * than the screen. What the native element gives for free is therefore done here
 * explicitly -- Escape closes, focus moves in on open and back out on close, focus is
 * kept inside while it is open, the page behind cannot scroll, and the backdrop is inert
 * to anything but dismissal.
 *
 * <p>The focus trap and the scroll lock were missing, and the comment above used to claim
 * the dialog was modal without either. Tabbing walked straight out of the panel into the
 * page behind it and the body kept scrolling, so "modal" was true of the pixels and false
 * of the keyboard.
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
}: {
  open: boolean
  onClose: () => void
  title: string
  description?: string | undefined
  children: ReactNode
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const returnFocusTo = useRef<HTMLElement | null>(null)

  // Held in a ref so the effect below depends on `open` alone. Callers define onClose in
  // their component body, so it is a new function on every render -- and with controlled
  // inputs that is every keystroke. Depending on it re-ran this effect mid-typing and
  // threw focus back to the first field after one character.
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  useEffect(() => {
    if (!open) {
      return
    }
    returnFocusTo.current = document.activeElement as HTMLElement | null

    const FOCUSABLE =
      'a[href], input:not([disabled]), button:not([disabled]), select:not([disabled]),' +
      ' textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

    /** Everything tabbable inside the panel, in document order. */
    function focusable(): HTMLElement[] {
      return Array.from(panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])
    }

    // The first focusable thing inside, or the panel itself, so a screen reader starts
    // reading the dialog rather than wherever the page happened to be.
    ;(focusable()[0] ?? panelRef.current)?.focus()

    // The page behind must not scroll under the dialog. Restored to whatever it was
    // rather than hard-coded back to "", so a page that sets its own overflow keeps it.
    const bodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab') {
        return
      }

      // The trap. Without it Tab leaves the panel and walks the page behind, which is
      // how a "modal" dialog stops being modal for anyone not using a mouse.
      const items = focusable()
      if (items.length === 0) {
        event.preventDefault()
        panelRef.current?.focus()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      const active = document.activeElement

      if (!event.shiftKey && (active === last || !panelRef.current?.contains(active))) {
        event.preventDefault()
        first?.focus()
      } else if (event.shiftKey && (active === first || !panelRef.current?.contains(active))) {
        event.preventDefault()
        last?.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = bodyOverflow
      returnFocusTo.current?.focus()
    }
  }, [open])

  if (!open) {
    return null
  }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 p-0 sm:items-center sm:p-4">
      {/* Dismissing by backdrop is a convenience, so it is not the only way out: the
          header carries a real Cancel/Close control too. */}
      <div className="absolute inset-0" aria-hidden="true" onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        aria-describedby={description === undefined ? undefined : 'dialog-description'}
        tabIndex={-1}
        className="relative w-full max-w-[420px] rounded-t-lg border border-line bg-surface-1 p-4 shadow-xl sm:rounded-lg"
      >
        <h2 className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-dim uppercase">
          {title}
        </h2>
        {description !== undefined && (
          <p id="dialog-description" className="mt-2 text-sm text-ink-muted">
            {description}
          </p>
        )}
        <div className="mt-4">{children}</div>
      </div>
    </div>
  )
}
