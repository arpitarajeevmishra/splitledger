package com.splitledger.expense.domain;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.money.SplitLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turning an expense into what each participant owes, in whole paise.
 *
 * <p>The one rule everything else serves: <b>the allocations sum to the total, exactly.</b>
 * Not within a paisa -- exactly. &#8377;100 split three ways is 3,334 + 3,333 + 3,333, and
 * the odd paisa goes to a specific person decided by a rule rather than to whoever the map
 * happened to iterate first. Anything else and the ledger stops balancing, which is the
 * single defect this product exists to not have.
 *
 * <p>EQUAL, PERCENT and SHARES are the same computation with different weights (LLD 4.2):
 * give everyone their exact proportional share rounded down, then hand the remaining paise
 * out one each, largest fractional remainder first. That is the largest-remainder method,
 * and it is why the error is under one paisa per participant rather than accumulating.
 * EXACT is not an allocation at all -- the caller has already decided, so it is validated
 * and used as given.
 *
 * <p>Ties are broken by user id, so the same input always produces the same output. That
 * matters more than it looks: without it, two replays of the same request could allocate
 * the odd paisa to different people and the second would not match the first.
 */
public final class SplitCalculator {

    private SplitCalculator() {
    }

    /**
     * Equal shares. The weights are all 1, so this is largest-remainder with the remainder
     * decided purely by id order -- the first n participants by id take the odd paise.
     */
    public static SequencedMap<UUID, Long> equalSplit(long totalPaise, Set<UUID> participants) {
        requireAmount(totalPaise);
        requireParticipantCount(participants.size());

        SequencedMap<UUID, Long> weights = new TreeMap<>();
        for (UUID participant : participants) {
            weights.put(participant, 1L);
        }
        return allocate(totalPaise, weights);
    }

    /**
     * Amounts the caller has already decided. Nothing is allocated; the sum is checked and
     * the figures are used as given.
     */
    public static SequencedMap<UUID, Long> exactSplit(long totalPaise, Map<UUID, Long> owed) {
        requireAmount(totalPaise);
        requireParticipantCount(owed.size());

        long sum = 0L;
        for (Map.Entry<UUID, Long> entry : owed.entrySet()) {
            long value = entry.getValue();
            if (value < 0L) {
                // A negative share would mean the group owes a participant for taking part
                // in a purchase, which is a different transaction entirely.
                throw new ApiException(ErrorCode.SPLIT_SUM_MISMATCH,
                        "A participant cannot owe a negative amount");
            }
            sum = Math.addExact(sum, value);
        }
        if (sum != totalPaise) {
            throw new ApiException(ErrorCode.SPLIT_SUM_MISMATCH,
                    "Split amounts add up to " + sum + " paise, but the expense is "
                            + totalPaise);
        }
        return new TreeMap<>(owed);
    }

    /** Percentages as basis points, so they sum exactly; 100.00% is 10,000. */
    public static SequencedMap<UUID, Long> percentSplit(long totalPaise,
            Map<UUID, Long> basisPoints) {
        requireAmount(totalPaise);
        requireParticipantCount(basisPoints.size());

        long sum = 0L;
        for (Map.Entry<UUID, Long> entry : basisPoints.entrySet()) {
            long value = entry.getValue();
            if (value <= 0L || value > SplitLimits.TOTAL_BASIS_POINTS) {
                throw new ApiException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        "Each share must be between 0.01% and 100%");
            }
            sum = Math.addExact(sum, value);
        }
        if (sum != SplitLimits.TOTAL_BASIS_POINTS) {
            throw new ApiException(ErrorCode.SPLIT_SUM_MISMATCH,
                    "Percentages add up to " + (sum / 100.0) + "%, not 100%");
        }
        return allocate(totalPaise, new TreeMap<>(basisPoints));
    }

    /** Share counts, capped so {@code total * weight} cannot overflow (finding C3). */
    public static SequencedMap<UUID, Long> sharesSplit(long totalPaise, Map<UUID, Long> shares) {
        requireAmount(totalPaise);
        requireParticipantCount(shares.size());

        for (Map.Entry<UUID, Long> entry : shares.entrySet()) {
            long value = entry.getValue();
            if (value < SplitLimits.MIN_SHARE || value > SplitLimits.MAX_SHARE) {
                throw new ApiException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                        "Each share count must be between " + SplitLimits.MIN_SHARE + " and "
                                + SplitLimits.MAX_SHARE);
            }
        }
        return allocate(totalPaise, new TreeMap<>(shares));
    }

    /**
     * The largest-remainder method (LLD 4.3).
     *
     * <p>Each participant gets {@code total * weight / sumOfWeights} rounded down, which
     * leaves fewer paise allocated than the total -- never more, and never by more than
     * one per participant. Those leftover paise go one each to the largest fractional
     * remainders, so the result is the closest whole-paise allocation to the exact
     * proportional one.
     *
     * <p>{@code multiplyExact} rather than {@code *}: the caps in {@link SplitLimits} are
     * what makes this product fit in a long, and if a caller ever reaches here without
     * having applied them, this throws instead of silently wrapping to a negative
     * allocation.
     */
    private static SequencedMap<UUID, Long> allocate(long totalPaise,
            SequencedMap<UUID, Long> weightsById) {
        long totalWeight = 0L;
        for (long weight : weightsById.values()) {
            totalWeight = Math.addExact(totalWeight, weight);
        }
        if (totalWeight <= 0L) {
            throw new ApiException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "The split has no positive weights to divide by");
        }

        SequencedMap<UUID, Long> allocation = new LinkedHashMap<>();
        List<Remainder> remainders = new ArrayList<>(weightsById.size());
        long allocated = 0L;

        for (Map.Entry<UUID, Long> entry : weightsById.entrySet()) {
            long numerator = Math.multiplyExact(totalPaise, entry.getValue());
            long base = numerator / totalWeight;
            allocation.put(entry.getKey(), base);
            allocated = Math.addExact(allocated, base);
            remainders.add(new Remainder(entry.getKey(), numerator % totalWeight));
        }

        // Strictly less than the participant count, because each participant was rounded
        // down by less than one paisa.
        long leftover = totalPaise - allocated;
        // Ties go to the lower user id. Two other things already point the same way --
        // the weights arrive in a TreeMap, and List.sort is stable -- so removing this
        // line happens to change nothing today. It stays because the guarantee is "the
        // odd paisa goes to the lowest id", and that should be stated where it is
        // decided rather than left resting on two incidental properties of the code
        // above it. SplitCalculatorTest pins the outcome, not the mechanism.
        remainders.sort(Comparator.comparingLong(Remainder::remainder).reversed()
                .thenComparing(Remainder::userId));
        for (int i = 0; i < leftover; i++) {
            allocation.merge(remainders.get(i).userId(), 1L, Long::sum);
        }
        return allocation;
    }

    private record Remainder(UUID userId, long remainder) {
    }

    private static void requireAmount(long totalPaise) {
        if (totalPaise < SplitLimits.MIN_EXPENSE_PAISE
                || totalPaise > SplitLimits.MAX_EXPENSE_PAISE) {
            throw new ApiException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "An expense must be between 1 paisa and "
                            + SplitLimits.MAX_EXPENSE_PAISE + " paise");
        }
    }

    private static void requireParticipantCount(int count) {
        if (count < SplitLimits.MIN_PARTICIPANTS || count > SplitLimits.MAX_PARTICIPANTS) {
            throw new ApiException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "An expense needs between " + SplitLimits.MIN_PARTICIPANTS + " and "
                            + SplitLimits.MAX_PARTICIPANTS + " participants");
        }
    }
}
