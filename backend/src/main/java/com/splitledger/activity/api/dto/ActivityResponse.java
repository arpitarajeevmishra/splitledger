package com.splitledger.activity.api.dto;

import com.splitledger.activity.domain.ActivityEntry;
import com.splitledger.activity.domain.ActivityKind;
import java.time.Instant;
import java.util.UUID;

/**
 * @param actorId null only for an expense voided before the columns recording it existed;
 *     a client should render "someone" rather than blank
 * @param movedMoney whether this entry explains a change in the balances, so a client can
 *     offer "only show what changed the numbers" without hardcoding the kinds
 */
public record ActivityResponse(
        ActivityKind kind,
        UUID subjectId,
        UUID actorId,
        Instant at,
        String description,
        Long amountPaise,
        UUID counterpartyId,
        boolean movedMoney) {

    public static ActivityResponse of(ActivityEntry entry) {
        return new ActivityResponse(entry.kind(), entry.subjectId(), entry.actorId(),
                entry.at(), entry.description(), entry.amountPaise(), entry.counterpartyId(),
                entry.movedMoney());
    }
}
