package com.splitledger.common.money;

import java.io.Serializable;
import java.util.Objects;

/**
 * An amount in integer paise (ADR-002).
 *
 * <p>Never a {@code double} and never a {@code BigDecimal}: binary floating point cannot
 * represent 0.1, and in a ledger that error accumulates until a balance is visibly wrong.
 * Paise is also the unit Razorpay speaks, so nothing is converted at the boundary.
 *
 * <p><b>Deliberately unbounded and signed.</b> The &#8377;10,00,000 ceiling in ADR-002
 * applies to a single <em>expense amount</em>, not to money as such -- a balance is the sum
 * of many expenses and is negative exactly when someone owes. Putting that cap here would
 * make the value object unable to represent the balances it exists to compute. The cap
 * lives with the split rules, in {@link SplitLimits}, which is where it is actually a rule.
 *
 * <p>Arithmetic uses the exact variants, so an overflow is an exception rather than a
 * silently wrapped balance.
 */
public record Money(long paise) implements Comparable<Money>, Serializable {

    public static final Money ZERO = new Money(0L);

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    /** Convenience for tests and fixtures; rupees are never a storage unit. */
    public static Money ofRupees(long rupees) {
        return new Money(Math.multiplyExact(rupees, 100L));
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    public Money negated() {
        return new Money(Math.negateExact(paise));
    }

    public boolean isZero() {
        return paise == 0L;
    }

    public boolean isPositive() {
        return paise > 0L;
    }

    public boolean isNegative() {
        return paise < 0L;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(paise, other.paise);
    }

    /**
     * Formats as rupees for logs and messages, never for storage or transport.
     *
     * <p>Uses integer division rather than any decimal type, so the formatting path cannot
     * reintroduce the rounding this class exists to prevent.
     */
    @Override
    public String toString() {
        long abs = Math.abs(paise);
        return (paise < 0 ? "-" : "") + "\u20b9" + (abs / 100L) + "." + String.format("%02d", abs % 100L);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Money money && money.paise == paise;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(paise);
    }
}
