package com.splitledger.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.money.SplitLimits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The worked examples from the design docs, and the refusals.
 *
 * <p>The property tests prove the invariants hold everywhere; these pin the specific
 * answers a human can check by hand, which is what makes a disagreement debuggable.
 */
class SplitCalculatorTest {

    /** Sorted, so "the lowest id" in an assertion means something definite. */
    private static List<UUID> sortedIds(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        ids.sort(UUID::compareTo);
        return ids;
    }

    @Test
    void theLldsWorkedExample() {
        // LLD 4.3: 10,000 paise among 3 -> 3,333 each, one paisa left over.
        List<UUID> people = sortedIds(3);

        Map<UUID, Long> allocation =
                SplitCalculator.equalSplit(10_000L, new LinkedHashSet<>(people));

        assertThat(allocation.values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(10_000L);
        assertThat(allocation).containsValues(3_334L, 3_333L);
    }

    @Test
    void theOddPaisaGoesToTheLowestUserId() {
        // Every remainder is equal here, so this is purely the tie-break. Pinning the
        // outcome rather than the mechanism: if the ordering is ever reversed or dropped
        // in a way that changes the answer, this fails.
        List<UUID> people = sortedIds(3);

        Map<UUID, Long> allocation =
                SplitCalculator.equalSplit(10_000L, new LinkedHashSet<>(people));

        assertThat(allocation.get(people.get(0))).isEqualTo(3_334L);
        assertThat(allocation.get(people.get(1))).isEqualTo(3_333L);
        assertThat(allocation.get(people.get(2))).isEqualTo(3_333L);
    }

    @Test
    void theTieBreakDoesNotDependOnTheOrderTheCallerBuiltTheMap() {
        List<UUID> people = sortedIds(3);
        LinkedHashSet<UUID> forwards = new LinkedHashSet<>(people);
        LinkedHashSet<UUID> backwards = new LinkedHashSet<>(people.reversed());

        assertThat(SplitCalculator.equalSplit(10_000L, forwards))
                .isEqualTo(SplitCalculator.equalSplit(10_000L, backwards));
    }

    @Test
    void sharesAllocateInProportion() {
        List<UUID> people = sortedIds(3);
        // 1:2:3 of 60,000 paise -> 10,000 / 20,000 / 30,000, no remainder at all.
        Map<UUID, Long> allocation = SplitCalculator.sharesSplit(60_000L, Map.of(
                people.get(0), 1L, people.get(1), 2L, people.get(2), 3L));

        assertThat(allocation.get(people.get(0))).isEqualTo(10_000L);
        assertThat(allocation.get(people.get(1))).isEqualTo(20_000L);
        assertThat(allocation.get(people.get(2))).isEqualTo(30_000L);
    }

    @Test
    void percentagesAreBasisPointsSoAThirdEachIsExpressible() {
        List<UUID> people = sortedIds(3);
        // 33.33 + 33.33 + 33.34 -- percentages must sum to exactly 100%.
        Map<UUID, Long> allocation = SplitCalculator.percentSplit(10_000L, Map.of(
                people.get(0), 3_333L, people.get(1), 3_333L, people.get(2), 3_334L));

        assertThat(allocation.values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(10_000L);
    }

    @Test
    void exactAmountsThatDoNotAddUpAreRejected() {
        List<UUID> people = sortedIds(2);

        assertThatThrownBy(() -> SplitCalculator.exactSplit(10_000L,
                Map.of(people.get(0), 5_000L, people.get(1), 4_999L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SPLIT_SUM_MISMATCH);
    }

    @Test
    void aNegativeExactShareIsRejectedEvenIfTheTotalWorksOut() {
        List<UUID> people = sortedIds(2);

        // 11,000 + (-1,000) sums correctly and is still nonsense: a participant does not
        // get paid for taking part in a purchase.
        assertThatThrownBy(() -> SplitCalculator.exactSplit(10_000L,
                Map.of(people.get(0), 11_000L, people.get(1), -1_000L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SPLIT_SUM_MISMATCH);
    }

    @Test
    void percentagesThatDoNotSumToOneHundredAreRejected() {
        List<UUID> people = sortedIds(2);

        assertThatThrownBy(() -> SplitCalculator.percentSplit(10_000L,
                Map.of(people.get(0), 5_000L, people.get(1), 4_000L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SPLIT_SUM_MISMATCH);
    }

    @Test
    void aShareCountOverTheCapIsRejectedBeforeAnyArithmetic() {
        List<UUID> people = sortedIds(2);

        // The cap is what keeps total * weight inside a long (C3), so it is refused
        // rather than clamped.
        assertThatThrownBy(() -> SplitCalculator.sharesSplit(10_000L,
                Map.of(people.get(0), SplitLimits.MAX_SHARE + 1, people.get(1), 1L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    void anAmountOverTheCapIsRejected() {
        assertThatThrownBy(() -> SplitCalculator.equalSplit(
                SplitLimits.MAX_EXPENSE_PAISE + 1, Set.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    void aZeroAmountExpenseIsRejected() {
        assertThatThrownBy(() -> SplitCalculator.equalSplit(0L, Set.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    void tooManyParticipantsIsRejected() {
        Set<UUID> crowd = new LinkedHashSet<>(sortedIds(SplitLimits.MAX_PARTICIPANTS + 1));

        assertThatThrownBy(() -> SplitCalculator.equalSplit(10_000L, crowd))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    void noParticipantsIsRejected() {
        assertThatThrownBy(() -> SplitCalculator.equalSplit(10_000L, Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AMOUNT_OUT_OF_RANGE);
    }

    @Test
    void theSmallestPossibleExpenseStillBalances() {
        // One paisa among three: two get nothing, one gets the paisa, and it still sums.
        List<UUID> people = sortedIds(3);
        Map<UUID, Long> allocation =
                SplitCalculator.equalSplit(1L, new LinkedHashSet<>(people));

        assertThat(allocation.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1L);
        assertThat(allocation.get(people.get(0))).isEqualTo(1L);
        assertThat(new HashMap<>(allocation)).containsValue(0L);
    }
}
