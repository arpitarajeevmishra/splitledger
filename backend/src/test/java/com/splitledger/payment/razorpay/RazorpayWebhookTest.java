package com.splitledger.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.payment.port.ProviderEvent;
import com.splitledger.payment.port.ProviderWebhook;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook verification (SL-603).
 *
 * <p>The bodies here are written out as literal strings rather than built from objects,
 * because that is the only way to test what the signature actually covers: a body produced
 * by serialising a map would be re-serialised identically by the implementation, and the
 * raw-body requirement would pass by accident.
 */
class RazorpayWebhookTest {

    private static final String WEBHOOK_SECRET = "a-webhook-secret";
    private static final String ORDER = "order_MkL9xQ2p";
    private static final String PAYMENT = "pay_MkL9xQ2p";

    private static final String CAPTURED = "{\"entity\":\"event\",\"event\":\"payment.captured\","
            + "\"contains\":[\"payment\"],\"payload\":{\"payment\":{\"entity\":{\"id\":\""
            + PAYMENT + "\",\"amount\":45000,\"currency\":\"INR\",\"status\":\"captured\","
            + "\"order_id\":\"" + ORDER + "\"}}},\"created_at\":1700000000}";

    private RazorpayProvider provider() {
        return provider(WEBHOOK_SECRET);
    }

    private RazorpayProvider provider(String webhookSecret) {
        return new RazorpayProvider(
                new RazorpayProperties("rzp_test_key", "api-secret", webhookSecret,
                        "https://stub.invalid", Duration.ofSeconds(2), Duration.ofSeconds(10)),
                RestClient.builder(), new ObjectMapper());
    }

    /** Razorpay's webhook scheme, computed here rather than by the class under test. */
    private static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ProviderWebhook delivery(String body, String signature, String eventId) {
        Map<String, String> headers = new java.util.HashMap<>();
        if (signature != null) {
            headers.put("x-razorpay-signature", signature);
        }
        if (eventId != null) {
            headers.put("x-razorpay-event-id", eventId);
        }
        headers.put("content-type", "application/json");
        return new ProviderWebhook(body.getBytes(StandardCharsets.UTF_8), headers);
    }

    @Test
    void readsACapturedPaymentOutOfAGenuineDelivery() {
        Optional<ProviderEvent> event = provider().verifiedEvent(
                delivery(CAPTURED, sign(CAPTURED, WEBHOOK_SECRET), "evt_1"));

        assertThat(event).isPresent();
        assertThat(event.get().eventId()).isEqualTo("evt_1");
        assertThat(event.get().type()).isEqualTo("payment.captured");
        assertThat(event.get().isPaymentCaptured()).isTrue();
        // The order id is the only route back to a settlement, and it is nested three
        // levels down in their payload.
        assertThat(event.get().orderId()).isEqualTo(ORDER);
        assertThat(event.get().paymentId()).isEqualTo(PAYMENT);
    }

    @Test
    void refusesADeliveryWhoseBodyWasChangedAfterSigning() {
        // The attack this exists to stop: a real delivery, re-aimed at a different order.
        String signature = sign(CAPTURED, WEBHOOK_SECRET);
        String edited = CAPTURED.replace(ORDER, "order_SomeoneElses");

        assertThat(provider().verifiedEvent(delivery(edited, signature, "evt_1"))).isEmpty();
    }

    @Test
    void refusesASignatureOverAReserialisedCopyOfTheSameJson() {
        // The same document, formatted differently: semantically identical, different
        // bytes, different MAC. This is why the raw body is carried through unparsed --
        // if the implementation ever parsed and re-serialised before verifying, this
        // delivery would start passing and a genuine one would start failing.
        String prettyish = CAPTURED.replace(",\"", ", \"");

        assertThat(provider().verifiedEvent(
                delivery(CAPTURED, sign(prettyish, WEBHOOK_SECRET), "evt_1"))).isEmpty();
    }

    @Test
    void refusesASignatureMadeWithTheApiSecretInsteadOfTheWebhookSecret() {
        // Easy misconfiguration, and one that must fail closed: Razorpay signs deliveries
        // with the dashboard's webhook secret, not with the key pair's secret.
        assertThat(provider().verifiedEvent(
                delivery(CAPTURED, sign(CAPTURED, "api-secret"), "evt_1"))).isEmpty();
    }

    @Test
    void refusesADeliveryWithNoSignatureAtAll() {
        assertThat(provider().verifiedEvent(delivery(CAPTURED, null, "evt_1"))).isEmpty();
    }

    @Test
    void refusesEverythingWhenNoWebhookSecretIsConfigured() {
        // Keys set, webhook secret blank. Every delivery is unverifiable, so every delivery
        // is refused -- rather than a public endpoint that believes whatever arrives.
        assertThat(provider("").verifiedEvent(
                delivery(CAPTURED, sign(CAPTURED, WEBHOOK_SECRET), "evt_1"))).isEmpty();
        assertThat(provider(null).verifiedEvent(
                delivery(CAPTURED, sign(CAPTURED, WEBHOOK_SECRET), "evt_1"))).isEmpty();
    }

    @Test
    void refusesASignedDeliveryThatCarriesNoEventId() {
        // Perfect signature, no way to recognise the retry that follows it. Razorpay
        // redelivers until it gets a 2xx, so accepting this would mean accepting something
        // that cannot be made idempotent -- for a ledger posting, the one unacceptable thing.
        assertThat(provider().verifiedEvent(
                delivery(CAPTURED, sign(CAPTURED, WEBHOOK_SECRET), null))).isEmpty();
        assertThat(provider().verifiedEvent(
                delivery(CAPTURED, sign(CAPTURED, WEBHOOK_SECRET), "   "))).isEmpty();
    }

    @Test
    void refusesASignedDeliveryItCannotParse() {
        String notJson = "this is not json at all";

        assertThat(provider().verifiedEvent(
                delivery(notJson, sign(notJson, WEBHOOK_SECRET), "evt_1"))).isEmpty();
    }

    @Test
    void acceptsAnEventAboutSomethingOtherThanAPaymentAndSaysSo() {
        // Verified, and nothing this application acts on. It comes back present, with no
        // order, so the caller can record that it arrived and move on rather than treating
        // a legitimate event as a forgery.
        String refund = "{\"entity\":\"event\",\"event\":\"refund.created\","
                + "\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\"}}},"
                + "\"created_at\":1700000000}";

        Optional<ProviderEvent> event = provider().verifiedEvent(
                delivery(refund, sign(refund, WEBHOOK_SECRET), "evt_2"));

        assertThat(event).isPresent();
        assertThat(event.get().type()).isEqualTo("refund.created");
        assertThat(event.get().isPaymentCaptured()).isFalse();
        assertThat(event.get().orderId()).isNull();
    }

    @Test
    void readsAnAuthorisedButNotCapturedEventWithoutTreatingItAsPaid() {
        // payment.authorized means the money is reserved, not taken. Confirming on it
        // would settle a debt against funds that can still be released.
        String authorised = CAPTURED.replace("payment.captured", "payment.authorized");

        Optional<ProviderEvent> event = provider().verifiedEvent(
                delivery(authorised, sign(authorised, WEBHOOK_SECRET), "evt_3"));

        assertThat(event).isPresent();
        assertThat(event.get().isPaymentCaptured()).isFalse();
    }

    @Test
    void theRawBodyCannotBeEditedAfterItWasVerified() {
        byte[] body = CAPTURED.getBytes(StandardCharsets.UTF_8);
        ProviderWebhook webhook = new ProviderWebhook(body,
                Map.of("x-razorpay-signature", sign(CAPTURED, WEBHOOK_SECRET),
                        "x-razorpay-event-id", "evt_1"));

        // Mutating the caller's array must not change what was verified, or "verified" is
        // a statement about a moment rather than about the bytes being stored.
        body[0] = 'X';

        assertThat(provider().verifiedEvent(webhook)).isPresent();
    }
}
