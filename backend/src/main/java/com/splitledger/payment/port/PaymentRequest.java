package com.splitledger.payment.port;

import java.util.UUID;

/**
 * What a provider needs to take a payment, owned by the payment module.
 *
 * <p>Deliberately not the {@code Settlement} aggregate, although ADR-006's sketch passes
 * it. A provider taking a settlement makes {@code payment} depend on {@code settlement},
 * and {@code settlement} already depends on {@code payment} for the port -- which is a
 * module cycle, and ArchUnit refuses it. The rule is right: once those two can see each
 * other, nothing stops gateway details leaking into the ledger-facing code.
 *
 * @param reference the settlement this is for, passed back in callbacks so the payment can
 *     be matched to what it settles. An opaque id to the provider.
 */
public record PaymentRequest(UUID reference, long amountPaise, String currency) {
}
