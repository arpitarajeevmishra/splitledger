/**
 * Money in the browser: integer paise, parsed and formatted without ever constructing a
 * float (ADR-002).
 *
 * <p>`parseFloat('0.1')` is 0.1000000000000000055511151231257827, and three of those add
 * up to something that is visibly not 0.3. The backend avoids this with `long` paise; the
 * browser has no integer type to hide behind, so the parsing is done on the digits
 * themselves and the only arithmetic is on values already known to be whole numbers well
 * inside `Number.MAX_SAFE_INTEGER`, where it is exact.
 */

/** Mirrors SplitLimits.MAX_EXPENSE_PAISE: ₹10,00,000. */
export const MAX_EXPENSE_PAISE = 100_000_000

export class InvalidAmountError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'InvalidAmountError'
  }
}

/**
 * Only what a person types into an amount field: digits, at most one dot, optional commas
 * as thousand separators. Deliberately strict -- see the notes on each rejection below.
 */
const AMOUNT = /^(\d{1,3}(?:,\d{2,3})*|\d*)(?:\.(\d*))?$/

/**
 * `"1,000.50"` becomes `100050`.
 *
 * Rejects rather than guesses, in every case where guessing would move money:
 *
 * - `"1.005"` — more precision than a paisa exists in. Rounding it would silently
 *   discard or invent half a paisa, and in a ledger that is the whole problem.
 * - `"-1"` — an expense is something that was spent. A negative is either a refund,
 *   which is its own kind of entry, or a typo.
 * - `"1e3"` — scientific notation only parses through a float, and accepting it here
 *   would mean doing exactly what this module exists to avoid.
 * - `""` — nothing typed is not zero.
 */
export function parsePaise(input: string): number {
  const trimmed = input.trim()
  if (trimmed === '') {
    throw new InvalidAmountError('Enter an amount')
  }
  if (trimmed.startsWith('-')) {
    throw new InvalidAmountError('Enter a positive amount')
  }

  const match = AMOUNT.exec(trimmed)
  if (match === null) {
    throw new InvalidAmountError('Enter an amount like 1,000.50')
  }

  const rupeeDigits = (match[1] ?? '').replace(/,/g, '')
  const fraction = match[2]

  if (rupeeDigits === '' && (fraction === undefined || fraction === '')) {
    // "." or "," alone matches the pattern but names no number.
    throw new InvalidAmountError('Enter an amount like 1,000.50')
  }
  if (fraction !== undefined && fraction.length > 2) {
    throw new InvalidAmountError('Amounts cannot be smaller than a paisa')
  }

  const paiseDigits = (fraction ?? '').padEnd(2, '0')
  const rupees = rupeeDigits === '' ? 0 : Number(rupeeDigits)
  const paise = paiseDigits === '' ? 0 : Number(paiseDigits)

  // Both operands are whole numbers from a bounded string, so this is exact. The check
  // afterwards is what keeps it that way.
  const total = rupees * 100 + paise
  if (!Number.isSafeInteger(total)) {
    throw new InvalidAmountError('That amount is too large')
  }
  if (total === 0) {
    throw new InvalidAmountError('Enter an amount greater than zero')
  }
  return total
}

/** The same rules, for form validation that wants a message rather than a throw. */
export function safeParsePaise(input: string): { paise: number } | { error: string } {
  try {
    return { paise: parsePaise(input) }
  } catch (error) {
    return { error: error instanceof InvalidAmountError ? error.message : 'Enter an amount' }
  }
}

/**
 * `100050` becomes `"₹1,000.50"`.
 *
 * Splits the digits as a string rather than dividing, so there is no division to be
 * imprecise, and groups the rupee part the Indian way -- 10,00,000 rather than
 * 1,000,000 -- because the product is INR-only and that is how the amount would be
 * written down.
 */
export function formatPaise(paise: number, options: { symbol?: boolean } = {}): string {
  if (!Number.isInteger(paise)) {
    // A fractional paisa reaching this function means something upstream used a float.
    throw new InvalidAmountError('Amounts must be whole paise')
  }
  const symbol = options.symbol === false ? '' : '\u20b9'
  const negative = paise < 0
  const digits = String(Math.abs(paise)).padStart(3, '0')
  const rupees = groupIndian(digits.slice(0, -2))
  const fraction = digits.slice(-2)
  return `${negative ? '-' : ''}${symbol}${rupees}.${fraction}`
}

/**
 * 1234567 -> "12,34,567". The last three digits group together, everything before them in
 * pairs. Done on the string so no number is ever reconstructed.
 */
function groupIndian(digits: string): string {
  if (digits.length <= 3) {
    return digits
  }
  const last3 = digits.slice(-3)
  const rest = digits.slice(0, -3)
  return `${rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${last3}`
}
