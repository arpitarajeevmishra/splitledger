package com.splitledger.payment.port;

/**
 * What a payer's browser hands back when a gateway checkout finishes (SL-602).
 *
 * <p>Every field is attacker-controlled. It arrives over an ordinary authenticated request
 * from a page this application does not control, so none of it may be believed until the
 * signature over it has been checked -- which is the whole reason this type exists rather
 * than three loose strings passed about.
 *
 * @param orderId the provider's order, which the caller must match against the one it
 *     stored. A genuine signature for a different order is still not a payment for this
 *     settlement.
 * @param paymentId the provider's payment, recorded once the signature holds
 * @param signature the provider's HMAC over the other two
 */
public record ProviderCallback(String orderId, String paymentId, String signature) {
}
