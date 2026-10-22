package com.splitledger.settlement.api.dto;

import com.splitledger.settlement.domain.Settlement;
import com.splitledger.settlement.domain.SettlementMethod;
import com.splitledger.settlement.domain.SettlementStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * @param expiresAt when this stops being actionable, so a client can show a countdown
 *     rather than letting someone discover it expired by trying
 * @param demoOnly true for every method that cannot move real money, so the UI can label
 *     it without knowing which methods those are (ADR-013)
 */
public record SettlementResponse(
        UUID id,
        UUID groupId,
        UUID fromUser,
        UUID toUser,
        long amountPaise,
        SettlementMethod method,
        SettlementStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant confirmedAt,
        long version,
        boolean demoOnly) {

    public static SettlementResponse of(Settlement settlement) {
        return new SettlementResponse(
                settlement.id(),
                settlement.groupId(),
                settlement.fromUser(),
                settlement.toUser(),
                settlement.amountPaise(),
                settlement.method(),
                settlement.status(),
                settlement.expiresAt(),
                settlement.createdAt(),
                settlement.confirmedAt(),
                settlement.version(),
                // Everything is demo-only in this product; the flag exists so the UI does
                // not have to hardcode that and silently stop labelling if it changes.
                true);
    }
}
