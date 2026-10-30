import type { ButtonHTMLAttributes } from 'react'

type Variant = 'primary' | 'ghost'

/** 36px compact height, 4px radius, flat fill, mono label -- design system, Buttons. */
const BASE =
  'inline-flex h-9 items-center justify-center rounded px-4 font-mono text-[13px] font-semibold ' +
  'transition-colors disabled:cursor-not-allowed disabled:opacity-50'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-accent text-on-accent hover:opacity-90',
  ghost: 'border border-line bg-transparent text-ink-muted hover:border-line-strong hover:text-ink',
}

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return <button className={`${BASE} ${VARIANTS[variant]} ${className}`} {...props} />
}
