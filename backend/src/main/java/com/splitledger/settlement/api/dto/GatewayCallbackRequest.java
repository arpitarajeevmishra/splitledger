package com.splitledger.settlement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the payer's browser reports when a gateway checkout finishes (SL-602).
 *
 * <p>Provider-neutral names on purpose. Razorpay calls these {@code razorpay_order_id},
 * {@code razorpay_payment_id} and {@code razorpay_signature}; the client maps them, and
 * this module stays free of the gateway's vocabulary (ADR-006).
 *
 * <p>Bounded lengths because these strings reach a MAC computation and a database column:
 * the columns are VARCHAR(64), and a megabyte of "signature" should be refused by
 * validation rather than hashed.
 */
public record GatewayCallbackRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 64) String paymentId,
        @NotBlank @Size(max = 128) String signature) {
}
