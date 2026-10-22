package com.splitledger.payment.port;

/**
 * What a provider hands back when asked to take a payment.
 *
 * @param orderId the provider's own reference, stored so a later callback can be matched
 *     to the settlement that caused it
 * @param payUrl where to send the payer, if anywhere. Null for methods that need no
 *     redirect -- a sandbox settles itself, and cash happens in a room.
 */
public record ProviderOrder(String orderId, String payUrl) {

    public static ProviderOrder withoutRedirect(String orderId) {
        return new ProviderOrder(orderId, null);
    }
}
