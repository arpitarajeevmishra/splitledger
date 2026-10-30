import type { InputHTMLAttributes, ReactNode } from 'react'

/** 40px input, 1px border, focus ring in info blue -- design system, Inputs. */
export function Field({
  label,
  error,
  hint,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & {
  label: string
  error?: string | undefined
  hint?: ReactNode | undefined
}) {
  const id = props.id ?? props.name
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-[13px] font-medium tracking-[0.01em] text-ink-muted">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        className={`h-10 rounded border bg-surface-1 px-3 font-mono text-sm text-ink
          placeholder:text-ink-dim
          ${error ? 'border-negative' : 'border-line focus:border-info'}`}
        {...props}
      />
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
