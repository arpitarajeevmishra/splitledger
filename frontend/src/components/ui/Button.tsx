import type { ButtonHTMLAttributes } from 'react'

type Variant = 'primary' | 'ghost'

/**
 * 36px compact height, 4px radius, flat fill, mono label -- design system, Buttons.
 *
 * <p>The transition names its properties rather than using `transition-colors`, because
 * Tailwind v4 includes `outline-color` in that shorthand. With the focus ring now drawn in
 * the base layer, the shorthand animated it: measured on the primary button, the ring
 * started at `rgb(255, 255, 255)` -- the label colour, invisible against a light page --
 * and only reached `--info` 150ms later. A keyboard ring has to be at full strength the
 * instant focus lands.
 */
const BASE =
  'inline-flex h-9 items-center justify-center rounded px-4 font-mono text-[13px] font-semibold ' +
  'transition-[color,background-color,border-color] ' +
  'disabled:cursor-not-allowed disabled:opacity-50'

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
