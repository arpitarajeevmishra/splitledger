package com.splitledger.payment.port;

import java.util.Map;

/**
 * A webhook delivery, exactly as it arrived (SL-603).
 *
 * <p>{@code rawBody} is the bytes, not a parsed object, and that is the whole point: the
 * signature covers the exact octets the provider sent. Parsing and re-serialising changes
 * key order, spacing and number formatting, so a MAC computed over the round-tripped form
 * would never match and the temptation would be to stop checking it.
 *
 * <p>Headers are passed whole, lower-cased, rather than picked apart by the caller. The
 * names a provider uses for its signature and its event id are the provider's business,
 * and naming them outside the payment module would put the gateway's vocabulary where
 * ADR-006 says it must not go.
 */
public record ProviderWebhook(byte[] rawBody, Map<String, String> headers) {

    public ProviderWebhook {
        rawBody = rawBody == null ? new byte[0] : rawBody.clone();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** @return the header's value, or null. Keys are lower-case. */
    public String header(String lowerCaseName) {
        return headers.get(lowerCaseName);
    }

    /** A defensive copy, so a verified body cannot be edited after it was verified. */
    @Override
    public byte[] rawBody() {
        return rawBody.clone();
    }
}
