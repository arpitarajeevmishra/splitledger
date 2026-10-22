package com.splitledger.settlement.api.dto;

import com.splitledger.common.payment.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * @param toUser who is being paid. The payer is always the caller and is deliberately not
 *     a field: accepting it would let one person record another's debts as settled.
 */
public record CreateSettlementRequest(
        @NotNull UUID toUser,
        @Min(1) @Max(100_000_000L) long amountPaise,
        @NotNull PaymentMethod method) {
}
