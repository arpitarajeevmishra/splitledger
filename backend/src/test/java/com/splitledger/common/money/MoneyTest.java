package com.splitledger.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void arithmeticIsExact() {
        // The whole reason this type exists: 0.1 + 0.2 is 0.30 and not 0.30000000000000004.
        assertThat(Money.ofPaise(10).plus(Money.ofPaise(20))).isEqualTo(Money.ofPaise(30));
        assertThat(Money.ofPaise(30).minus(Money.ofPaise(10))).isEqualTo(Money.ofPaise(20));
    }

    @Test
    void balancesMayBeNegative() {
        // A balance is the sum of many expenses and is negative exactly when someone owes.
        // The expense cap deliberately does not live here.
        Money owed = Money.ofPaise(100).minus(Money.ofPaise(250));

        assertThat(owed.isNegative()).isTrue();
        assertThat(owed).isEqualTo(Money.ofPaise(-150));
    }

    @Test
    void mayExceedTheSingleExpenseCeiling() {
        Money huge = Money.ofPaise(SplitLimits.MAX_EXPENSE_PAISE)
                .plus(Money.ofPaise(SplitLimits.MAX_EXPENSE_PAISE));

        assertThat(huge.paise()).isEqualTo(2 * SplitLimits.MAX_EXPENSE_PAISE);
    }

    @Test
    void overflowThrowsRatherThanWrappingToANegativeBalance() {
        // Silently wrapping is how a ledger reports that someone is owed nine quintillion
        // rupees, so the exact variants are used throughout.
        assertThatThrownBy(() -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofRupees(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void formatsRupeesWithoutDecimalArithmetic() {
        assertThat(Money.ofPaise(100050)).hasToString("\u20b91000.50");
        assertThat(Money.ofPaise(5)).hasToString("\u20b90.05");
        assertThat(Money.ZERO).hasToString("\u20b90.00");
    }

    @Test
    void formatsNegativesWithTheSignOutsideTheSymbol() {
        assertThat(Money.ofPaise(-150)).hasToString("-\u20b91.50");
        // The paise component is taken from the absolute value, or -1.5 would print as
        // -1.-50.
        assertThat(Money.ofPaise(-5)).hasToString("-\u20b90.05");
    }

    @Test
    void ordersByAmount() {
        assertThat(Money.ofPaise(-100)).isLessThan(Money.ZERO);
        assertThat(Money.ofPaise(100)).isGreaterThan(Money.ZERO);
        assertThat(Money.ofPaise(100)).isEqualByComparingTo(Money.ofPaise(100));
    }

    @Test
    void rupeesAreAConvenienceNotAStorageUnit() {
        assertThat(Money.ofRupees(1_000)).isEqualTo(Money.ofPaise(100_000));
    }
}
