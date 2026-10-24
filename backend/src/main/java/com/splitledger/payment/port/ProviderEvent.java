package com.splitledger.payment.port;

/**
 * What a verified webhook turns out to say (SL-603).
 *
 * <p>Only produced after the signature over the raw body has held, so unlike
 * {@link ProviderCallback} its contents may be acted on -- with one exception, which is
 * the whole of {@link #orderId()}: it is the provider's order, and the caller must still
 * look the settlement up *by* it rather than trusting any settlement id the payload might
 * also carry. A signed event is proof the provider said something, not permission to
 * believe the receipt field it said it about.
 *
 * @param eventId the provider's own id for this delivery, used to recognise a repeat
 * @param type what happened, in the provider's words, e.g. {@code payment.captured}
 * @param orderId the order this is about, the only way back to a settlement
 * @param paymentId the provider's payment, recorded when the event confirms one
 */
public record ProviderEvent(String eventId, String type, String orderId, String paymentId) {

    /** Razorpay's name for a payment that has been taken, not merely authorised. */
    public static final String PAYMENT_CAPTURED = "payment.captured";

    /** A payment that was tried and did not succeed. */
    public static final String PAYMENT_FAILED = "payment.failed";

    public boolean isPaymentCaptured() {
        return PAYMENT_CAPTURED.equals(type);
    }

    public boolean isPaymentFailed() {
        return PAYMENT_FAILED.equals(type);
    }
}
