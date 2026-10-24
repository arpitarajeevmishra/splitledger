package com.splitledger.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.payment.port.PaymentRequest;
import com.splitledger.payment.port.ProviderCallback;
import com.splitledger.payment.port.ProviderOrder;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Callback signature verification (SL-602).
 *
 * <p>The expected digests here are not copied from the implementation. They come from
 * Razorpay's published scheme -- HMAC-SHA256 of {@code order_id|payment_id} keyed with the
 * API secret -- computed independently below, so a change to how the payload is assembled
 * fails rather than quietly agreeing with itself.
 */
class RazorpaySignatureTest {

    private static final String SECRET = "thisisasecret";
    private static final String ORDER = "order_IluGWxBm9U8zJ8";
    private static final String PAYMENT = "pay_IluGWxBm9U8zJ8";

    private final RazorpayProvider provider = new RazorpayProvider(
            new RazorpayProperties("rzp_test_key", SECRET, "a-webhook-secret", "https://stub.invalid",
                    Duration.ofSeconds(2), Duration.ofSeconds(10)),
            RestClient.builder(), new ObjectMapper());

    /** Razorpay's scheme, written out here rather than called from the class under test. */
    private static String signedByRazorpay(String orderId, String paymentId, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(
                    (orderId + "|" + paymentId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void acceptsTheSignatureRazorpayWouldSend() {
        String signature = signedByRazorpay(ORDER, PAYMENT, SECRET);

        assertThat(provider.verifiedPaymentId(new ProviderCallback(ORDER, PAYMENT, signature)))
                .contains(PAYMENT);
    }

    @Test
    void refusesASignatureWithOneCharacterChanged() {
        String genuine = signedByRazorpay(ORDER, PAYMENT, SECRET);
        char first = genuine.charAt(0);
        String tampered = (first == 'a' ? 'b' : 'a') + genuine.substring(1);

        assertThat(provider.verifiedPaymentId(new ProviderCallback(ORDER, PAYMENT, tampered)))
                .isEmpty();
    }

    @Test
    void refusesAGenuineSignatureForADifferentPayment() {
        // The classic break: a real callback for a payment of their own, replayed with the
        // payment id swapped. The signature is genuine and covers the wrong words.
        String forAnotherPayment = signedByRazorpay(ORDER, "pay_somethingElse", SECRET);

        assertThat(provider.verifiedPaymentId(
                new ProviderCallback(ORDER, PAYMENT, forAnotherPayment))).isEmpty();
    }

    @Test
    void refusesAGenuineSignatureForADifferentOrder() {
        String forAnotherOrder = signedByRazorpay("order_somethingElse", PAYMENT, SECRET);

        assertThat(provider.verifiedPaymentId(
                new ProviderCallback(ORDER, PAYMENT, forAnotherOrder))).isEmpty();
    }

    @Test
    void refusesASignatureMadeWithAnotherKey() {
        // What a different merchant account, or a leaked test key, would produce.
        String wrongKey = signedByRazorpay(ORDER, PAYMENT, "someoneElsesSecret");

        assertThat(provider.verifiedPaymentId(new ProviderCallback(ORDER, PAYMENT, wrongKey)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-hex-at-all", "00", "deadbeef"})
    void refusesRubbishWithoutThrowing(String rubbish) {
        // These arrive from a page this application does not control. A malformed signature
        // must be a refusal, not a 500 -- an exception here would turn a bad callback into
        // an error the payer cannot act on, and a hint that the input reached something.
        assertThat(provider.verifiedPaymentId(new ProviderCallback(ORDER, PAYMENT, rubbish)))
                .isEmpty();
    }

    @Test
    void refusesANullSignature() {
        assertThat(provider.verifiedPaymentId(new ProviderCallback(ORDER, PAYMENT, null)))
                .isEmpty();
    }

    @Test
    void refusesMissingIdsRatherThanSigningTheWordNull() {
        // "null|null" is a perfectly signable payload. If the ids were interpolated without
        // checking, a forged signature over that string would verify.
        String overNulls = signedByRazorpay("null", "null", SECRET);

        assertThat(provider.verifiedPaymentId(new ProviderCallback(null, null, overNulls)))
                .isEmpty();
        assertThat(provider.verifiedPaymentId(new ProviderCallback(ORDER, null,
                signedByRazorpay(ORDER, "null", SECRET)))).isEmpty();
    }

    @Test
    void acceptsTheSameSignatureInUpperCase() {
        // Razorpay sends lower-case hex; a client that uppercases it is not an attacker,
        // and producing any casing of the right digest still needs the secret.
        String upper = signedByRazorpay(ORDER, PAYMENT, SECRET).toUpperCase(java.util.Locale.ROOT);

        assertThat(provider.verifiedPaymentId(new ProviderCallback(ORDER, PAYMENT, upper)))
                .contains(PAYMENT);
    }

    @Test
    void theDigestMatchesAnIndependentlyComputedVector() {
        // Computed outside this codebase (python hmac, sha256) so that changing both the
        // implementation and the helper above cannot make this agree with itself.
        assertThat(RazorpaySignatures.hmacHex("order_1|pay_1", "secret"))
                .isEqualTo("52115a0d3400de9e86aade1f1b6eba9e8974604f4e267a9e9a16633a4c8dd2cb");
        // And the payload is order, pipe, payment -- not the reverse, and not concatenated
        // without a separator.
        assertThat(RazorpaySignatures.callbackPayload("order_1", "pay_1"))
                .isEqualTo("order_1|pay_1");
    }

    @Test
    void aProviderWithNoSignatureSchemeRefusesByDefault() {
        // The port's default. It matters that it refuses rather than accepts: a provider
        // that cannot verify a browser's word must never be able to confirm a settlement,
        // and the sandbox reaches the same end by polling.
        PaymentProvider noScheme = new PaymentProvider() {
            @Override
            public PaymentMethod method() {
                return PaymentMethod.SANDBOX;
            }

            @Override
            public ProviderOrder initiate(PaymentRequest request) {
                return ProviderOrder.withoutRedirect("sandbox_x");
            }
        };

        Optional<String> answer = noScheme.verifiedPaymentId(
                new ProviderCallback(ORDER, PAYMENT, "anything"));

        assertThat(answer).isEmpty();
    }
}
