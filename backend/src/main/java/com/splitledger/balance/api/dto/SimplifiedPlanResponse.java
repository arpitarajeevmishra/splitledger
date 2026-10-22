package com.splitledger.balance.api.dto;

import com.splitledger.balance.domain.Transfer;
import java.util.List;
import java.util.UUID;

/**
 * @param transfers who should pay whom, fewest payments first
 * @param settled true when nobody owes anything, so a client can say "all settled up"
 *     rather than rendering an empty list with no explanation
 */
public record SimplifiedPlanResponse(List<SuggestedTransfer> transfers, boolean settled) {

    public record SuggestedTransfer(UUID from, UUID to, long amountPaise) {
    }

    public static SimplifiedPlanResponse of(List<Transfer> transfers) {
        return new SimplifiedPlanResponse(
                transfers.stream()
                        .map(transfer -> new SuggestedTransfer(transfer.from(), transfer.to(),
                                transfer.amount().paise()))
                        .toList(),
                transfers.isEmpty());
    }
}
