import { describe, expect, it } from 'vitest'
import { formatPaise, InvalidAmountError, parsePaise, safeParsePaise } from './money'

describe('parsePaise', () => {
  it('parses a plain decimal', () => {
    // The case the whole module exists for: parseFloat('0.1') * 100 is 10.000000000000002.
    expect(parsePaise('0.1')).toBe(10)
    expect(parsePaise('0.01')).toBe(1)
    expect(parsePaise('1')).toBe(100)
    expect(parsePaise('1.5')).toBe(150)
  })

  it('accepts thousand separators', () => {
    expect(parsePaise('1,000.50')).toBe(100050)
    expect(parsePaise('10,00,000')).toBe(100000000)
  })

  it('accepts a leading dot', () => {
    expect(parsePaise('.5')).toBe(50)
    expect(parsePaise('.05')).toBe(5)
  })

  it('ignores surrounding whitespace', () => {
    expect(parsePaise('  12.34  ')).toBe(1234)
  })

  it('refuses more precision than a paisa', () => {
    // Rounding would silently discard or invent half a paisa, which is the entire
    // category of bug this project exists to avoid.
    expect(() => parsePaise('1.005')).toThrow(InvalidAmountError)
    expect(() => parsePaise('0.001')).toThrow(InvalidAmountError)
  })

  it('refuses a negative amount', () => {
    expect(() => parsePaise('-1')).toThrow(InvalidAmountError)
    expect(() => parsePaise('-0.5')).toThrow(InvalidAmountError)
  })

  it('refuses scientific notation', () => {
    // '1e3' only becomes 1000 by going through a float, which is the one thing this
    // module must not do.
    expect(() => parsePaise('1e3')).toThrow(InvalidAmountError)
    expect(() => parsePaise('1E3')).toThrow(InvalidAmountError)
  })

  it('refuses an empty or meaningless string', () => {
    expect(() => parsePaise('')).toThrow(InvalidAmountError)
    expect(() => parsePaise('   ')).toThrow(InvalidAmountError)
    expect(() => parsePaise('.')).toThrow(InvalidAmountError)
    expect(() => parsePaise('abc')).toThrow(InvalidAmountError)
    expect(() => parsePaise('12abc')).toThrow(InvalidAmountError)
  })

  it('refuses zero, which is not an expense', () => {
    expect(() => parsePaise('0')).toThrow(InvalidAmountError)
    expect(() => parsePaise('0.00')).toThrow(InvalidAmountError)
  })

  it('refuses a second dot or a stray sign', () => {
    expect(() => parsePaise('1.2.3')).toThrow(InvalidAmountError)
    expect(() => parsePaise('+1')).toThrow(InvalidAmountError)
  })

  it('is exact where a float would not be', () => {
    // 0.1 + 0.2 as floats is 0.30000000000000004; as paise it is simply 30.
    expect(parsePaise('0.1') + parsePaise('0.2')).toBe(30)
    // Three ten-paise amounts, which floats famously cannot sum cleanly.
    expect(parsePaise('0.1') * 3).toBe(30)
  })
})

describe('safeParsePaise', () => {
  it('returns a value rather than throwing', () => {
    expect(safeParsePaise('1,000.50')).toEqual({ paise: 100050 })
  })

  it('returns a message a form can show', () => {
    const result = safeParsePaise('1.005')
    expect(result).toHaveProperty('error')
    expect((result as { error: string }).error).toMatch(/paisa/)
  })
})

describe('formatPaise', () => {
  it('formats rupees and paise', () => {
    expect(formatPaise(100050)).toBe('\u20b91,000.50')
    expect(formatPaise(0)).toBe('\u20b90.00')
    expect(formatPaise(5)).toBe('\u20b90.05')
    expect(formatPaise(50)).toBe('\u20b90.50')
    expect(formatPaise(100)).toBe('\u20b91.00')
  })

  it('groups the Indian way', () => {
    // 10,00,000 rather than 1,000,000 -- the product is INR-only and that is how the
    // amount would be written down.
    expect(formatPaise(100000000)).toBe('\u20b910,00,000.00')
    expect(formatPaise(10000000)).toBe('\u20b91,00,000.00')
    expect(formatPaise(1000000)).toBe('\u20b910,000.00')
    expect(formatPaise(100000)).toBe('\u20b91,000.00')
    expect(formatPaise(99999)).toBe('\u20b9999.99')
  })

  it('puts the sign outside the symbol', () => {
    expect(formatPaise(-150)).toBe('-\u20b91.50')
    // Taken from the absolute value, or -1.50 would print as -1.-50.
    expect(formatPaise(-5)).toBe('-\u20b90.05')
  })

  it('can omit the symbol for table columns', () => {
    expect(formatPaise(100050, { symbol: false })).toBe('1,000.50')
  })

  it('refuses a fractional paisa, which means a float got in upstream', () => {
    expect(() => formatPaise(10.5)).toThrow(InvalidAmountError)
  })

  it('round-trips with parsePaise', () => {
    for (const input of ['0.01', '1', '1.5', '1,000.50', '10,00,000']) {
      expect(parsePaise(formatPaise(parsePaise(input), { symbol: false }))).toBe(parsePaise(input))
    }
  })
})
