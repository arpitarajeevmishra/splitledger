package com.splitledger.settlement.api.dto;

import com.splitledger.settlement.domain.Settlement;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.settlement.domain.SettlementStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * @param gatewayOrderId the provider's reference, so a client can tell an order that
 *     exists from one that does not, and correlate a support question with a provider
 *     dashboard. Not a secret: it identifies an order, it does not authorise anything
 * @param gatewayPaymentId set once the provider reports the money moved
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
        PaymentMethod method,
        SettlementStatus status,
        String gatewayOrderId,
        String gatewayPaymentId,
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
                settlement.gatewayOrderId(),
                settlement.gatewayPaymentId(),
                settlement.expiresAt(),
                settlement.createdAt(),
                settlement.confirmedAt(),
                settlement.version(),
                // Everything is demo-only in this product; the flag exists so the UI does
                // not have to hardcode that and silently stop labelling if it changes.
                true);
    }
}
