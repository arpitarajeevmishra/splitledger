package com.splitledger.payment.razorpay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay's credentials and endpoint (SL-601).
 *
 * <p>{@code keyId} is not a secret: it goes to the browser so Checkout can open an order,
 * and Razorpay publishes it as the public half of the pair. {@code keySecret} is, and only
 * ever leaves here as an Authorization header.
 *
 * <p>{@code baseUrl} is configurable so a test can point the provider at a stub instead of
 * at api.razorpay.com. It is not there so anyone can repoint production: the only way to
 * send real orders elsewhere is to change the deployment's own configuration, which is the
 * same trust boundary as the key itself.
 *
 * @param baseUrl Razorpay's API root, no trailing slash
 * @param connectTimeout how long to wait for the socket. Short: a gateway that cannot be
 *     reached in two seconds is not going to be reached.
 * @param readTimeout how long to wait for an answer. A payment request thread blocked on a
 *     slow gateway is a thread not serving anyone, and the payer is watching a spinner.
 */
@ConfigurationProperties(prefix = "app.payments.razorpay")
public record RazorpayProperties(
        String keyId,
        String keySecret,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public RazorpayProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.razorpay.com"
                : baseUrl.replaceAll("/+$", "");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    /** True when both halves of the pair are present; one without the other is a mistake. */
    public boolean configured() {
        return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }
}
