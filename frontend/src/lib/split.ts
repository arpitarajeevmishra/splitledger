import { MAX_EXPENSE_PAISE } from './money'

/**
 * The same largest-remainder allocation the backend uses, so the form can show what a
 * split will actually come to before anyone submits it.
 *
 * <p><b>This is a deliberate duplicate of server logic, and that is a real cost.</b> If
 * the two ever disagree, the preview lies about who absorbs the odd paisa -- and the
 * ledger, not this file, will be right. Two things keep them honest: `split.test.ts`
 * asserts the same worked examples as the Java `SplitCalculatorTest`, including the
 * tie-break, so drift fails a test rather than misleading a user quietly.
 *
 * <p>The alternative was a round trip per keystroke to a preview endpoint. Authoritative,
 * but it makes typing an amount depend on the network, and the thing being previewed is
 * arithmetic rather than anything only the server knows.
 */

export const MAX_PARTICIPANTS = 50
export const MIN_SHARE = 1
export const MAX_SHARE = 1000
export const TOTAL_BASIS_POINTS = 10_000

export type SplitType = 'EQUAL' | 'EXACT' | 'PERCENT' | 'SHARES'

export type Allocation = Map<string, number>

/**
 * Largest-remainder: everyone's exact proportional share rounded down, then the leftover
 * paise handed out one each, largest fractional remainder first, ties to the lowest id.
 *
 * The tie-break matters as much as the arithmetic -- it is what makes the preview name
 * the same person the server will.
 */
function allocate(
  totalPaise: number,
  weights: Map<string, number>,
): { allocation: Allocation; absorbers: string[] } {
  const totalWeight = [...weights.values()].reduce((sum, weight) => sum + weight, 0)
  if (totalWeight <= 0) {
    return { allocation: new Map(), absorbers: [] }
  }

  const allocation: Allocation = new Map()
  const remainders: { userId: string; remainder: number }[] = []
  let allocated = 0

  // Sorted by id so the iteration order cannot depend on how the caller built the map.
  for (const userId of [...weights.keys()].sort()) {
    const weight = weights.get(userId) ?? 0
    const numerator = totalPaise * weight
    const base = Math.floor(numerator / totalWeight)
    allocation.set(userId, base)
    allocated += base
    remainders.push({ userId, remainder: numerator % totalWeight })
  }

  const leftover = totalPaise - allocated
  remainders.sort((a, b) => b.remainder - a.remainder || (a.userId < b.userId ? -1 : 1))

  // Recorded as it is handed out, not inferred afterwards from who owes most. With
  // unequal weights a larger share is simply a larger share -- only the extra paisa from
  // this loop is something a person is absorbing.
  const absorbers: string[] = []
  for (let i = 0; i < leftover; i++) {
    const userId = remainders[i]?.userId
    if (userId !== undefined) {
      allocation.set(userId, (allocation.get(userId) ?? 0) + 1)
      absorbers.push(userId)
    }
  }
  return { allocation, absorbers: absorbers.sort() }
}

export type SplitPreview = {
  /** What each participant would owe, in paise. Empty when the input is not yet valid. */
  allocation: Allocation
  /** Total minus what the inputs account for. Zero means the split is submittable. */
  remainingPaise: number
  /** Who gets the extra paise the division could not place, in id order. */
  absorbers: string[]
  /** Null when submittable; otherwise why not, in words a person can act on. */
  error: string | null
}

const EMPTY: SplitPreview = {
  allocation: new Map(),
  remainingPaise: 0,
  absorbers: [],
  error: null,
}

/**
 * What this split would come to, and what is stopping it.
 *
 * @param values for EXACT these are paise, for PERCENT basis points, for SHARES a share
 *     count, and for EQUAL they are ignored
 */
export function previewSplit(
  splitType: SplitType,
  totalPaise: number,
  participants: string[],
  values: Map<string, number>,
): SplitPreview {
  if (participants.length === 0) {
    return { ...EMPTY, error: 'Choose at least one person' }
  }
  if (participants.length > MAX_PARTICIPANTS) {
    return { ...EMPTY, error: `At most ${MAX_PARTICIPANTS} people can share one expense` }
  }
  if (totalPaise <= 0 || totalPaise > MAX_EXPENSE_PAISE) {
    return { ...EMPTY, error: 'Enter an amount first' }
  }

  if (splitType === 'EXACT') {
    const assigned = participants.reduce((sum, id) => sum + (values.get(id) ?? 0), 0)
    const remaining = totalPaise - assigned
    const allocation: Allocation = new Map(participants.map((id) => [id, values.get(id) ?? 0]))
    if (participants.some((id) => (values.get(id) ?? 0) < 0)) {
      return { ...EMPTY, allocation, error: 'Nobody can owe a negative amount' }
    }
    return {
      allocation,
      remainingPaise: remaining,
      absorbers: [],
      // The counter already shows the number; this says what to do about it.
      error: remaining === 0 ? null : 'Split amounts must add up to the total',
    }
  }

  if (splitType === 'PERCENT') {
    const assigned = participants.reduce((sum, id) => sum + (values.get(id) ?? 0), 0)
    if (participants.some((id) => (values.get(id) ?? 0) <= 0)) {
      return { ...EMPTY, error: 'Every share must be above zero' }
    }
    if (assigned !== TOTAL_BASIS_POINTS) {
      return {
        ...EMPTY,
        remainingPaise: 0,
        error: `Percentages must add up to 100%, not ${(assigned / 100).toFixed(2)}%`,
      }
    }
    return withAbsorbers(
      allocate(totalPaise, new Map(participants.map((id) => [id, values.get(id) ?? 0]))),
      totalPaise,
    )
  }

  if (splitType === 'SHARES') {
    const outOfRange = participants.some((id) => {
      const share = values.get(id) ?? 0
      return share < MIN_SHARE || share > MAX_SHARE
    })
    if (outOfRange) {
      return { ...EMPTY, error: `Each share must be between ${MIN_SHARE} and ${MAX_SHARE}` }
    }
    return withAbsorbers(
      allocate(totalPaise, new Map(participants.map((id) => [id, values.get(id) ?? 0]))),
      totalPaise,
    )
  }

  return withAbsorbers(allocate(totalPaise, new Map(participants.map((id) => [id, 1]))), totalPaise)
}

/**
 * Packages what allocate already decided.
 *
 * The absorbers are shown rather than hidden: somebody has to take what three does not
 * divide into, and a split that silently gives one person an extra paisa is the kind of
 * detail that erodes trust in every other number on the screen.
 */
function withAbsorbers(
  allocated: { allocation: Allocation; absorbers: string[] },
  totalPaise: number,
): SplitPreview {
  const sum = [...allocated.allocation.values()].reduce((total, share) => total + share, 0)
  return {
    allocation: allocated.allocation,
    remainingPaise: totalPaise - sum,
    absorbers: allocated.absorbers,
    error: null,
  }
}
