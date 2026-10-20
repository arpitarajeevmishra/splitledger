import { useEffect, useRef, type ReactNode } from 'react'

/**
 * A modal dialog.
 *
 * <p>Hand-rolled rather than using the native &lt;dialog&gt; element: jsdom's support for
 * showModal() is partial, so the component tests would be testing the polyfill gap rather
 * than the screen. The three things the native element gives for free are therefore done
 * here explicitly -- Escape closes, focus moves in on open and back out on close, and the
 * backdrop is inert to anything but dismissal.
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

  useEffect(() => {
    if (!open) {
      return
    }
    returnFocusTo.current = document.activeElement as HTMLElement | null
    // The first focusable thing inside, or the panel itself, so a screen reader starts
    // reading the dialog rather than wherever the page happened to be.
    const first = panelRef.current?.querySelector<HTMLElement>(
      'input, button, select, textarea, [tabindex]:not([tabindex="-1"])',
    )
    ;(first ?? panelRef.current)?.focus()

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onClose()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      returnFocusTo.current?.focus()
    }
  }, [open, onClose])

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
