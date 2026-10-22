package com.splitledger.common.money;

/**
 * The bounds that make the split arithmetic safe (LLD 4.1, findings C2 and C3).
 *
 * <p>These are not cosmetic product limits. {@code allocate()} computes
 * {@code total * weight}, so the ceilings on amount and weight are precisely what keeps
 * that product inside a {@code long}: 10^8 paise times 10^4 basis points is 10^12, which
 * fits with room to spare, while an unbounded share count does not.
 *
 * <p>Each is enforced twice -- here before allocating, and again as a {@code CHECK}
 * constraint or Bean Validation annotation at the edges. The duplication is the point: a
 * new caller that forgets the check still cannot write a row that breaks the invariant.
 */
public final class SplitLimits {

    /** A single expense is at least 1 paisa; a zero-amount expense is not an expense. */
    public static final long MIN_EXPENSE_PAISE = 1L;

    /** &#8377;10,00,000. The overflow ceiling, not a judgement about what people spend. */
    public static final long MAX_EXPENSE_PAISE = 100_000_000L;

    public static final int MIN_PARTICIPANTS = 1;

    /** Bounds the allocation loop and the postings written in one transaction (M6). */
    public static final int MAX_PARTICIPANTS = 50;

    public static final long MIN_SHARE = 1L;
    public static final long MAX_SHARE = 1_000L;

    /** 100.00% in basis points; percentages are integers so they can be summed exactly. */
    public static final long TOTAL_BASIS_POINTS = 10_000L;

    private SplitLimits() {
    }
}
