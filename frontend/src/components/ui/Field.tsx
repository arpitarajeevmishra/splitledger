import { useState, type InputHTMLAttributes, type ReactNode } from 'react'

/** 40px input, 1px border, focus ring in info blue -- design system, Inputs. */
export function Field({
  label,
  error,
  hint,
  type,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & {
  label: string
  error?: string | undefined
  hint?: ReactNode | undefined
}) {
  const id = props.id ?? props.name
  // Deliberately component state and nothing more: it resets on every mount, so a
  // revealed password never survives a navigation, a reload or a back button. Remembering
  // the preference would mean the next person at this keyboard gets it revealed by default.
  const [revealed, setRevealed] = useState(false)
  const isPassword = type === 'password'

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-[13px] font-medium tracking-[0.01em] text-ink-muted">
        {label}
      </label>
      <div className="relative flex">
        <input
          id={id}
          type={isPassword && revealed ? 'text' : type}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          className={`h-10 w-full rounded border bg-surface-1 pl-3 font-mono text-sm text-ink
            placeholder:text-ink-dim
            ${isPassword ? 'pr-14' : 'pr-3'}
            ${error ? 'border-negative' : 'border-line focus:border-info'}`}
          {...props}
        />
        {isPassword && (
          /* The trailing end of a field is where extra features belong; the leading end
             states its purpose (HIG text-fields.md > Platform considerations > Mobile).

             A word rather than an eye: this product's affordances are typographic -- the
             notification bell is the word "Inbox" -- and an eye would be the only icon in
             it. "Show" and "Hide" are both four characters in a monospaced face, so the
             control is exactly the same width in both states and nothing shifts when it
             is pressed. The field's padding never has to change either.

             h-11 is 44px on a 40px field, overflowing 2px into the 8px gap above and
             below: invisible, and the hit region buttons.md asks for.

             transition-[color], not transition-colors: Tailwind v4 includes outline-color
             in that set, so the shorthand fades in the focus ring that index.css draws on
             every focus-visible control. Measured mid-transition it sat at a third of its
             contrast, and a keyboard ring has to be at full strength the instant focus
             lands. Only the hover tint eases. */
          <button
            type="button"
            onClick={() => setRevealed((shown) => !shown)}
            aria-label={revealed ? 'Hide password' : 'Show password'}
            className="absolute top-1/2 right-0 flex h-11 w-14 -translate-y-1/2 cursor-pointer
              items-center justify-end rounded pr-3 font-mono text-[11px] font-medium
              tracking-[0.06em] text-ink-muted uppercase transition-[color] hover:text-ink"
          >
            {revealed ? 'Hide' : 'Show'}
          </button>
        )}
      </div>
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-negative">
          {error}
        </p>
      ) : (
        hint
      )}
    </div>
  )
}
