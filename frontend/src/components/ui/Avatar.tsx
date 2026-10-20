/**
 * Initials, following the mockups' member rows. Derived from the name rather than stored,
 * because there are no uploaded images in this product and a coloured circle that is
 * always present beats an avatar that is usually missing.
 */
function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  const first = words[0]
  if (first === undefined) {
    return '?'
  }
  const last = words.length > 1 ? words[words.length - 1] : undefined
  const second = last === undefined ? '' : (last[0] ?? '')
  return ((first[0] ?? '') + second).toUpperCase()
}

export function Avatar({ name, className = '' }: { name: string; className?: string }) {
  return (
    <span
      // Decorative: the name it is derived from is always rendered next to it, so
      // announcing the initials too would just repeat the row.
      aria-hidden="true"
      className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded bg-surface-2 font-mono text-[12px] font-semibold text-ink-muted ${className}`}
    >
      {initialsOf(name)}
    </span>
  )
}
