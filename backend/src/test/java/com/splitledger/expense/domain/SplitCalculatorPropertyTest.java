package com.splitledger.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.common.money.SplitLimits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The properties that have to hold for every input, not just the ones anyone thought to
 * write down (LLD 4.4). This is the one place in the project where examples genuinely are
 * not enough: the failure mode is a single paisa going missing on some particular
 * combination of total and weights, and nobody picks that combination by hand.
 */
class SplitCalculatorPropertyTest {

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static long sum(Map<UUID, Long> allocation) {
        return allocation.values().stream().mapToLong(Long::longValue).sum();
    }

    // --- EQUAL ---------------------------------------------------------------

    @Property(tries = 2000)
    void equalSplitAlwaysSumsToTheTotal(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll @IntRange(min = 1, max = SplitLimits.MAX_PARTICIPANTS) int participants) {
        Set<UUID> people = new LinkedHashSet<>(ids(participants));

        SequencedMap<UUID, Long> allocation = SplitCalculator.equalSplit(total, people);

        assertThat(sum(allocation)).isEqualTo(total);
        assertThat(allocation).hasSize(participants);
    }

    @Property(tries = 2000)
    void equalSplitNeverAllocatesANegativeAmount(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll @IntRange(min = 1, max = SplitLimits.MAX_PARTICIPANTS) int participants) {
        SequencedMap<UUID, Long> allocation =
                SplitCalculator.equalSplit(total, new LinkedHashSet<>(ids(participants)));

        assertThat(allocation.values()).allSatisfy(share -> assertThat(share).isNotNegative());
    }

    @Property(tries = 2000)
    void equalSharesDifferByAtMostOnePaisa(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll @IntRange(min = 1, max = SplitLimits.MAX_PARTICIPANTS) int participants) {
        SequencedMap<UUID, Long> allocation =
                SplitCalculator.equalSplit(total, new LinkedHashSet<>(ids(participants)));

        long min = allocation.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = allocation.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        // The whole promise of largest-remainder: nobody is short by more than the one
        // paisa that could not be divided.
        assertThat(max - min).isLessThanOrEqualTo(1L);
    }

    // --- SHARES --------------------------------------------------------------

    @Provide
    Arbitrary<Map<UUID, Long>> shareMaps() {
        return Arbitraries.integers().between(1, SplitLimits.MAX_PARTICIPANTS)
                .flatMap(count -> Arbitraries.longs()
                        .between(SplitLimits.MIN_SHARE, SplitLimits.MAX_SHARE)
                        .list().ofSize(count)
                        .map(shares -> {
                            List<UUID> people = ids(count);
                            Map<UUID, Long> weights = new HashMap<>();
                            for (int i = 0; i < count; i++) {
                                weights.put(people.get(i), shares.get(i));
                            }
                            return weights;
                        }));
    }

    @Property(tries = 2000)
    void sharesSplitAlwaysSumsToTheTotal(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll("shareMaps") Map<UUID, Long> shares) {
        assertThat(sum(SplitCalculator.sharesSplit(total, shares))).isEqualTo(total);
    }

    @Property(tries = 2000)
    void sharesSplitNeverAllocatesANegativeAmount(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll("shareMaps") Map<UUID, Long> shares) {
        assertThat(SplitCalculator.sharesSplit(total, shares).values())
                .allSatisfy(share -> assertThat(share).isNotNegative());
    }

    @Property(tries = 2000)
    void everyShareIsWithinOnePaisaOfItsExactProportion(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll("shareMaps") Map<UUID, Long> shares) {
        long totalWeight = shares.values().stream().mapToLong(Long::longValue).sum();
        SequencedMap<UUID, Long> allocation = SplitCalculator.sharesSplit(total, shares);

        allocation.forEach((userId, allocated) -> {
            // The exact proportional share, as a rational, compared without floating
            // point: |allocated * totalWeight - total * weight| < totalWeight means the
            // difference is strictly under one paisa.
            long weight = shares.get(userId);
            long scaledDifference =
                    Math.abs(allocated * totalWeight - total * weight);
            assertThat(scaledDifference).isLessThan(totalWeight);
        });
    }

    @Property(tries = 1000)
    void sharesSplitIsDeterministic(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll("shareMaps") Map<UUID, Long> shares) {
        // Same input in a different map implementation, twice. This pins that the result
        // does not depend on the caller's map ordering; *which* participant receives the
        // odd paisa is pinned separately, by example, in SplitCalculatorTest.
        assertThat(SplitCalculator.sharesSplit(total, shares))
                .isEqualTo(SplitCalculator.sharesSplit(total, new HashMap<>(shares)));
    }

    // --- PERCENT -------------------------------------------------------------

    @Provide
    Arbitrary<Map<UUID, Long>> basisPointMaps() {
        return Arbitraries.integers().between(1, 40).flatMap(count ->
                Arbitraries.longs().between(1L, SplitLimits.TOTAL_BASIS_POINTS)
                        .list().ofSize(count)
                        .map(raw -> {
                            // Scale an arbitrary positive vector so it sums to exactly
                            // 10,000, which is the only input percentSplit accepts.
                            long rawTotal = raw.stream().mapToLong(Long::longValue).sum();
                            List<UUID> people = ids(count);
                            Map<UUID, Long> points = new HashMap<>();
                            long assigned = 0L;
                            for (int i = 0; i < count - 1; i++) {
                                long share = Math.max(1L,
                                        raw.get(i) * (SplitLimits.TOTAL_BASIS_POINTS - count)
                                                / rawTotal);
                                points.put(people.get(i), share);
                                assigned += share;
                            }
                            points.put(people.get(count - 1),
                                    SplitLimits.TOTAL_BASIS_POINTS - assigned);
                            return points;
                        }));
    }

    @Property(tries = 2000)
    void percentSplitAlwaysSumsToTheTotal(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total,
            @ForAll("basisPointMaps") Map<UUID, Long> basisPoints) {
        assertThat(sum(SplitCalculator.percentSplit(total, basisPoints))).isEqualTo(total);
    }

    // --- at the caps, not merely below them ----------------------------------

    @Property(tries = 500)
    void holdsAtTheBoundaryValues(
            @ForAll @IntRange(min = 1, max = SplitLimits.MAX_PARTICIPANTS) int participants) {
        // ADR-002's follow-up says the caps must be tested *at* the boundary, because that
        // is where total * weight is largest and an overflow would appear.
        Map<UUID, Long> maxShares = new HashMap<>();
        for (UUID id : ids(participants)) {
            maxShares.put(id, SplitLimits.MAX_SHARE);
        }

        SequencedMap<UUID, Long> allocation =
                SplitCalculator.sharesSplit(SplitLimits.MAX_EXPENSE_PAISE, maxShares);

        assertThat(sum(allocation)).isEqualTo(SplitLimits.MAX_EXPENSE_PAISE);
        assertThat(allocation.values()).allSatisfy(share -> assertThat(share).isPositive());
    }

    @Property(tries = 500)
    void aSingleParticipantGetsTheWholeAmount(
            @ForAll @LongRange(min = 1L, max = SplitLimits.MAX_EXPENSE_PAISE) long total) {
        UUID only = UUID.randomUUID();

        assertThat(SplitCalculator.equalSplit(total, Set.of(only))).containsEntry(only, total);
        assertThat(SplitCalculator.sharesSplit(total, Map.of(only, 7L)))
                .containsEntry(only, total);
        assertThat(SplitCalculator.percentSplit(total,
                Map.of(only, SplitLimits.TOTAL_BASIS_POINTS))).containsEntry(only, total);
    }

    @Property(tries = 1000)
    void exactSplitIsUsedAsGivenWhenItAddsUp(
            @ForAll @LongRange(min = 2L, max = 1_000_000L) long total,
            @ForAll @IntRange(min = 2, max = SplitLimits.MAX_PARTICIPANTS) int participants) {
        List<UUID> people = ids(participants);
        Map<UUID, Long> owed = new HashMap<>();
        long remaining = total;
        for (int i = 0; i < participants - 1; i++) {
            long share = remaining / (participants - i);
            owed.put(people.get(i), share);
            remaining -= share;
        }
        owed.put(people.get(participants - 1), remaining);

        assertThat(SplitCalculator.exactSplit(total, owed))
                .containsExactlyInAnyOrderEntriesOf(owed);
    }
}
