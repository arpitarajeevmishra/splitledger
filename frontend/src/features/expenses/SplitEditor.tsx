import type { GroupMember } from '../../api/types'
import { Avatar } from '../../components/ui/Avatar'
import { formatPaise } from '../../lib/money'
import type { SplitPreview, SplitType } from '../../lib/split'

const SPLIT_LABELS: Record<SplitType, string> = {
  EQUAL: 'Equally',
  EXACT: 'Exact amounts',
  PERCENT: 'Percentages',
  SHARES: 'Shares',
}

/** Short on purpose: every character here is one the member's name does not get. */
const UNIT_HINT: Record<SplitType, string> = {
  EQUAL: '',
  EXACT: '\u20b9',
  PERCENT: '%',
  SHARES: '\u00d7',
}

/**
 * Who is in, how much each owes, and what is still unaccounted for.
 *
 * <p>The remaining counter is the whole point of this component. A split that does not
 * add up is the one mistake the backend will refuse, so saying so while someone types
 * beats letting them submit and reading an error afterwards.
 */
export function SplitEditor({
  members,
  splitType,
  onSplitTypeChange,
  selected,
  onToggle,
  values,
  onValueChange,
  preview,
}: {
  members: GroupMember[]
  splitType: SplitType
  onSplitTypeChange: (splitType: SplitType) => void
  selected: Set<string>
  onToggle: (userId: string) => void
  values: Map<string, string>
  onValueChange: (userId: string, raw: string) => void
  preview: SplitPreview
}) {
  /**
   * What the strip below should say.
   *
   * `remainingPaise === 0` alone was doing two jobs badly. It is also true before anyone
   * has typed an amount -- the preview returns a zero remainder with an error set -- so a
   * blank form showed a green "Adds up". And once a split did add up, the label changed
   * to "Adds up" while the number stayed the remainder, which is zero by definition:
   * "Adds up ₹0.00", a sentence that reads as "these splits total nothing".
   */
  const allocated = [...preview.allocation.values()].reduce((sum, paise) => sum + paise, 0)
  const settled = preview.error === null && preview.remainingPaise === 0 && allocated > 0
  const nothingEnteredYet = !settled && preview.remainingPaise === 0

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        <span className="text-[13px] font-medium tracking-[0.01em] text-ink-muted">Split</span>
        <div className="flex flex-wrap gap-2">
          {(Object.keys(SPLIT_LABELS) as SplitType[]).map((type) => (
            <button
              key={type}
              type="button"
              aria-pressed={splitType === type}
              onClick={() => onSplitTypeChange(type)}
              /* Named properties rather than transition-colors: that shorthand includes
                 outline-color in Tailwind v4, which fades in the base layer's focus ring.
                 See Button.tsx. */
              className={`h-8 rounded border px-3 font-mono text-[11px] tracking-[0.04em] uppercase transition-[color,background-color,border-color] ${
                splitType === type
                  ? 'border-accent bg-accent text-on-accent'
                  : 'border-line text-ink-muted hover:border-line-strong hover:text-ink'
              }`}
            >
              {SPLIT_LABELS[type]}
            </button>
          ))}
        </div>
      </div>

      <ul className="rounded border border-line">
        {members.map((member) => {
          const isIn = selected.has(member.userId)
          const owed = preview.allocation.get(member.userId)
          const absorbs = preview.absorbers.includes(member.userId)
          return (
            <li
              key={member.userId}
              className="flex items-center gap-3 border-b border-line px-3 py-2 last:border-b-0"
            >
              <input
                type="checkbox"
                id={`split-${member.userId}`}
                checked={isIn}
                onChange={() => onToggle(member.userId)}
                className="h-4 w-4 accent-[var(--accent)]"
              />
              <Avatar name={member.name} className="h-7 w-7 text-[10px]" />
              <label
                htmlFor={`split-${member.userId}`}
                className="min-w-0 flex-1 cursor-pointer truncate text-sm text-ink"
              >
                {member.name}
              </label>

              {isIn && splitType !== 'EQUAL' && (
                <span className="flex shrink-0 items-center gap-1">
                  <input
                    aria-label={`${SPLIT_LABELS[splitType]} for ${member.name}`}
                    inputMode="decimal"
                    value={values.get(member.userId) ?? ''}
                    onChange={(event) => onValueChange(member.userId, event.target.value)}
                    className="h-8 w-20 rounded border border-line bg-surface-1 px-2 text-right font-mono text-xs text-ink focus:border-info"
                  />
                  <span className="w-3 font-mono text-[10px] text-ink-dim">
                    {UNIT_HINT[splitType]}
                  </span>
                </span>
              )}

              {isIn && owed !== undefined && (
                <span className="tabular w-16 shrink-0 text-right font-mono text-[11px] text-ink-muted">
                  {formatPaise(owed)}
                  {absorbs && (
                    // Somebody has to take what three does not divide into. Saying who
                    // beats letting them find a stray paisa on their own later.
                    <span
                      title="Takes the extra paisa this split cannot divide evenly"
                      className="ml-1 text-accent"
                    >
                      +
                    </span>
                  )}
                </span>
              )}
            </li>
          )
        })}
      </ul>

      <div
        role="status"
        className={`flex items-center justify-between rounded border px-3 py-2 text-xs ${
          settled
            ? 'border-positive/40 bg-positive/10 text-positive'
            : nothingEnteredYet
              ? 'border-line bg-surface-2 text-ink-dim'
              : 'border-negative/40 bg-negative/10 text-negative'
        }`}
      >
        <span className="font-mono tracking-[0.04em] uppercase">
          {settled ? 'Adds up to' : 'Remaining'}
        </span>
        <span className="tabular font-mono">
          {/* The total when it adds up, so the label is a true statement and the figure
              is the one worth checking: that the split equals the expense. */}
          {settled
            ? formatPaise(allocated)
            : nothingEnteredYet
              ? '—'
              : formatPaise(preview.remainingPaise)}
        </span>
      </div>

      {preview.absorbers.length > 0 && (
        <p className="text-xs text-ink-dim">
          {preview.absorbers.length === 1 ? 'One person takes' : 'Some people take'} an extra paisa,
          because this amount does not divide evenly.
        </p>
      )}

      {preview.error !== null && (
        <p role="alert" className="text-xs text-negative">
          {preview.error}
        </p>
      )}
    </div>
  )
}
