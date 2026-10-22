import { describe, expect, it } from 'vitest'
import { previewSplit, TOTAL_BASIS_POINTS } from './split'

/**
 * These mirror the Java SplitCalculatorTest on purpose.
 *
 * This file is the only thing standing between the preview and a quiet lie: if the
 * backend's allocation changes and this one does not, the form will name the wrong person
 * as absorbing the odd paisa and nothing else will notice. Same inputs, same expected
 * answers, including the tie-break.
 */

// Sorted, so "the lowest id" in an assertion means something definite -- and the
// backend breaks ties the same way.
const A = '11111111-1111-1111-1111-111111111111'
const B = '22222222-2222-2222-2222-222222222222'
const C = '33333333-3333-3333-3333-333333333333'

const none = new Map<string, number>()

describe('previewSplit — EQUAL', () => {
  it('matches the LLD worked example: 10,000 paise among three', () => {
    const preview = previewSplit('EQUAL', 10_000, [A, B, C], none)

    expect(preview.error).toBeNull()
    expect(preview.remainingPaise).toBe(0)
    expect([...preview.allocation.values()].reduce((s, v) => s + v, 0)).toBe(10_000)
    expect(preview.allocation.get(A)).toBe(3_334)
    expect(preview.allocation.get(B)).toBe(3_333)
    expect(preview.allocation.get(C)).toBe(3_333)
  })

  it('names the lowest id as absorbing the odd paisa, as the backend does', () => {
    const preview = previewSplit('EQUAL', 10_000, [A, B, C], none)

    // The tie-break, which is the single most likely thing to drift.
    expect(preview.absorbers).toEqual([A])
  })

  it('does not depend on the order the participants were listed', () => {
    const forwards = previewSplit('EQUAL', 10_000, [A, B, C], none)
    const backwards = previewSplit('EQUAL', 10_000, [C, B, A], none)

    expect([...backwards.allocation.entries()].sort()).toEqual(
      [...forwards.allocation.entries()].sort(),
    )
    expect(backwards.absorbers).toEqual(forwards.absorbers)
  })

  it('gives one participant the whole amount', () => {
    expect(previewSplit('EQUAL', 50_000, [A], none).allocation.get(A)).toBe(50_000)
  })

  it('handles the smallest possible expense', () => {
    // One paisa among three: two owe nothing, and it still sums.
    const preview = previewSplit('EQUAL', 1, [A, B, C], none)
    expect([...preview.allocation.values()].reduce((s, v) => s + v, 0)).toBe(1)
    expect(preview.allocation.get(A)).toBe(1)
    expect(preview.absorbers).toEqual([A])
  })

  it('refuses before an amount is entered', () => {
    expect(previewSplit('EQUAL', 0, [A, B], none).error).toMatch(/amount/i)
  })

  it('refuses with nobody chosen', () => {
    expect(previewSplit('EQUAL', 10_000, [], none).error).toMatch(/at least one/i)
  })
})

describe('previewSplit — SHARES', () => {
  it('allocates in proportion', () => {
    const shares = new Map([
      [A, 1],
      [B, 2],
      [C, 3],
    ])
    const preview = previewSplit('SHARES', 60_000, [A, B, C], shares)

    expect(preview.error).toBeNull()
    expect(preview.allocation.get(A)).toBe(10_000)
    expect(preview.allocation.get(B)).toBe(20_000)
    expect(preview.allocation.get(C)).toBe(30_000)
    // Divides evenly, so nobody absorbs anything.
    expect(preview.absorbers).toEqual([])
  })

  it('refuses a share count outside the cap the backend enforces', () => {
    const shares = new Map([
      [A, 1001],
      [B, 1],
    ])
    expect(previewSplit('SHARES', 10_000, [A, B], shares).error).toMatch(/between 1 and 1000/)
  })

  it('still sums exactly when the shares do not divide the total', () => {
    const shares = new Map([
      [A, 1],
      [B, 1],
      [C, 1],
    ])
    const preview = previewSplit('SHARES', 10_001, [A, B, C], shares)

    expect([...preview.allocation.values()].reduce((s, v) => s + v, 0)).toBe(10_001)
    expect(preview.remainingPaise).toBe(0)
  })
})

describe('previewSplit — PERCENT', () => {
  it('accepts basis points that add to exactly 100%', () => {
    const points = new Map([
      [A, 3_333],
      [B, 3_333],
      [C, 3_334],
    ])
    const preview = previewSplit('PERCENT', 10_000, [A, B, C], points)

    expect(preview.error).toBeNull()
    expect([...preview.allocation.values()].reduce((s, v) => s + v, 0)).toBe(10_000)
  })

  it('says how far off the percentages are rather than just refusing', () => {
    const points = new Map([
      [A, 5_000],
      [B, 4_000],
    ])
    // A bare "invalid" leaves someone hunting for the missing 10%.
    expect(previewSplit('PERCENT', 10_000, [A, B], points).error).toMatch(/90\.00%/)
  })

  it('refuses a zero share', () => {
    const points = new Map([
      [A, TOTAL_BASIS_POINTS],
      [B, 0],
    ])
    expect(previewSplit('PERCENT', 10_000, [A, B], points).error).toMatch(/above zero/i)
  })
})

describe('previewSplit — EXACT', () => {
  it('is submittable only when the amounts add up to the total', () => {
    const exact = new Map([
      [A, 5_000],
      [B, 5_000],
    ])
    const preview = previewSplit('EXACT', 10_000, [A, B], exact)

    expect(preview.error).toBeNull()
    expect(preview.remainingPaise).toBe(0)
  })

  it('reports what is left so the counter can show it', () => {
    const exact = new Map([
      [A, 5_000],
      [B, 4_000],
    ])
    const preview = previewSplit('EXACT', 10_000, [A, B], exact)

    expect(preview.remainingPaise).toBe(1_000)
    expect(preview.error).toMatch(/add up/i)
  })

  it('reports an over-allocation as a negative remainder', () => {
    const exact = new Map([
      [A, 7_000],
      [B, 5_000],
    ])
    // Negative rather than clamped: "₹-20.00 left" is wrong in a way a person can act on.
    expect(previewSplit('EXACT', 10_000, [A, B], exact).remainingPaise).toBe(-2_000)
  })

  it('refuses a negative share even when the total works out', () => {
    const exact = new Map([
      [A, 11_000],
      [B, -1_000],
    ])
    expect(previewSplit('EXACT', 10_000, [A, B], exact).error).toMatch(/negative/i)
  })
})
