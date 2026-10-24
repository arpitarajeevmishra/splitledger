package com.splitledger.payment.razorpay;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.payment.port.PaymentRequest;
import com.splitledger.payment.port.ProviderCallback;
import com.splitledger.payment.port.ProviderEvent;
import com.splitledger.payment.port.ProviderWebhook;
import com.splitledger.payment.port.ProviderOrder;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Razorpay, in test mode (SL-601).
 *
 * <p>No Razorpay SDK. The Orders API this needs is one authenticated POST returning one
 * id, and the SDK brings its own HTTP stack and its own JSON library -- a second one, next
 * to the Jackson this project already uses. {@code RestClient} is already on the classpath
 * and the ArchUnit rule that keeps the gateway in this package applies either way.
 *
 * <p>A bean only when a key pair is configured. Without it the demo must still start, and
 * the sandbox provider must still be the thing that answers (ADR-013): a provider that
 * exists but cannot authenticate would turn every settlement into a 502.
 *
 * <p>Not idempotent itself, and does not need to be: {@code SettlementService} stores the
 * order id under the group's row lock and will not ask twice (LLD 8). That is the right
 * place for it -- idempotency here would mean this class keeping its own record of what it
 * has already done, which is a second source of truth about a settlement.
 */
@Component
@ConditionalOnProperty(prefix = "app.payments.razorpay", name = "key-id")
public class RazorpayProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(RazorpayProvider.class);

    private final RestClient client;
    private final ObjectMapper json;
    private final String keySecret;
    private final String webhookSecret;

    @Autowired
    RazorpayProvider(RazorpayProperties properties, ObjectMapper json) {
        this(properties, RestClient.builder().requestFactory(timeouts(properties)), json);
    }

    /**
     * Visible for testing: the builder is where MockRestServiceServer attaches.
     *
     * <p>It deliberately does not set a request factory. Binding a mock server to a builder
     * installs one, and setting another here would quietly replace it -- the test would
     * then make real calls to api.razorpay.com and pass or fail for reasons of its own.
     */
    RazorpayProvider(RazorpayProperties properties, RestClient.Builder builder,
            ObjectMapper json) {
        this.json = json;
        if (!properties.configured()) {
            // The @ConditionalOnProperty above only sees key-id. A key id with no secret
            // would otherwise authenticate as nobody and fail on the first order.
            throw new IllegalStateException(
                    "app.payments.razorpay.key-id is set but key-secret is not");
        }
        this.keySecret = properties.keySecret();
        this.webhookSecret = properties.webhookSecret();
        this.client = builder
                .baseUrl(properties.baseUrl())
                // Basic auth, which is what Razorpay's API takes: the key id as the user
                // and the secret as the password. Set once here so no call site can forget.
                .defaultHeaders(headers ->
                        headers.setBasicAuth(properties.keyId(), properties.keySecret()))
                .build();
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.RAZORPAY;
    }

    /**
     * Razorpay signs {@code order_id|payment_id} with the API secret and Checkout hands the
     * result to the page. Verifying it here is what turns "the browser says it paid" into
     * something this application may act on.
     *
     * <p>Says nothing about whether the order belongs to the settlement being confirmed:
     * the caller holds the stored order id and must compare it. A signature is proof that
     * Razorpay said these words, not proof that they are about the right debt.
     */
    @Override
    public Optional<String> verifiedPaymentId(ProviderCallback callback) {
        if (callback.orderId() == null || callback.paymentId() == null) {
            // Nothing to sign, so nothing can verify. Hashing nulls into the payload would
            // produce a digest that happily matches a forged signature over "null|null".
            return Optional.empty();
        }
        String payload = RazorpaySignatures.callbackPayload(callback.orderId(),
                callback.paymentId());
        if (!RazorpaySignatures.matches(payload, keySecret, callback.signature())) {
            return Optional.empty();
        }
        return Optional.of(callback.paymentId());
    }

    /**
     * Razorpay signs the delivery body with the webhook secret and sends the digest in
     * {@code X-Razorpay-Signature}; the de-duplication key is {@code X-Razorpay-Event-Id},
     * which is a header and not a body field.
     *
     * <p>A delivery with no event id is refused even when its signature is perfect. Without
     * one there is no way to recognise the same delivery twice, and Razorpay retries --
     * accepting it would mean accepting something that cannot be made idempotent, which
     * for a ledger posting is the one thing that must not happen.
     */
    @Override
    public Optional<ProviderEvent> verifiedEvent(ProviderWebhook webhook) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            // Keys configured but no webhook secret: every delivery is unverifiable, so
            // every delivery is refused. Louder than it looks -- the alternative is a
            // gateway endpoint that trusts whatever arrives.
            log.warn("A Razorpay webhook arrived but no webhook secret is configured");
            return Optional.empty();
        }
        if (!RazorpaySignatures.matchesRaw(webhook.rawBody(), webhookSecret,
                webhook.header("x-razorpay-signature"))) {
            return Optional.empty();
        }

        String eventId = webhook.header("x-razorpay-event-id");
        if (eventId == null || eventId.isBlank()) {
            log.warn("A signed Razorpay webhook arrived with no event id and cannot be "
                    + "de-duplicated");
            return Optional.empty();
        }

        Event event;
        try {
            event = json.readValue(webhook.rawBody(), Event.class);
        } catch (JacksonException ex) {
            // Signed, so it really is from Razorpay, and still unreadable. Worth a log line
            // rather than a silent refusal: this is our parser disagreeing with their
            // payload, not an attack.
            log.warn("A signed Razorpay webhook could not be parsed", ex);
            return Optional.empty();
        }

        String orderId = event.orderId();
        String paymentId = event.paymentId();
        if (orderId == null || paymentId == null) {
            // An event about something other than a payment on an order. Verified, and
            // nothing this application can act on.
            return Optional.of(new ProviderEvent(eventId, event.event(), null, null));
        }
        return Optional.of(new ProviderEvent(eventId, event.event(), orderId, paymentId));
    }

    @Override
    public ProviderOrder initiate(PaymentRequest request) {
        if (request.amountPaise() < PaymentMethod.RAZORPAY.minimumPaise()) {
            // Checked again here, although SettlementService refuses it at creation. Not
            // defence in depth for its own sake: this is the boundary, the floor is a
            // condition of the call itself, and a caller that is not SettlementService
            // would otherwise send Razorpay a request it is known to refuse.
            throw new ApiException(ErrorCode.SETTLEMENT_BELOW_GATEWAY_MINIMUM,
                    "Card payments start at " + PaymentMethod.RAZORPAY.minimumPaise() + " paise");
        }

        OrderResponse order;
        try {
            order = client.post()
                    .uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    // Serialised here, and sent as a String, so the request carries a
                    // Content-Length. Handing RestClient the Map streams it instead, and
                    // the JDK client then frames a hundred-byte body as chunked -- which was
                    // visible the moment the call was pointed at a stub that reports framing.
                    // Not because Razorpay is known to object: because a body whose length
                    // is known cannot be refused for its framing by anything in between.
                    .body(body(Map.of(
                            // Paise, which is both Razorpay's unit and ours, so there is
                            // no conversion here to get wrong (ADR-002).
                            "amount", request.amountPaise(),
                            "currency", request.currency(),
                            // Our settlement id, handed back on every callback about this
                            // order. The only thing that ties a payment to what it settles.
                            "receipt", request.reference().toString(),
                            // Capture on authorisation. Without it a payment is authorised
                            // and never taken, and the settlement waits for money that is
                            // reserved and will be released.
                            "payment_capture", 1)))
                    .retrieve()
                    .body(OrderResponse.class);
        } catch (RestClientException ex) {
            // The response body can carry the key id and the failing request; the message
            // the payer sees must not. The correlation id is already in the log line.
            log.warn("Razorpay refused an order for settlement {}", request.reference(), ex);
            throw new ApiException(ErrorCode.GATEWAY_ERROR,
                    "The payment gateway could not start this payment. Please try again.");
        }

        if (order == null || order.id() == null || order.id().isBlank()) {
            // A 200 with no id is not success. Treating it as one would store an empty
            // order reference, and no callback could ever be matched back to it.
            log.warn("Razorpay returned no order id for settlement {}", request.reference());
            throw new ApiException(ErrorCode.GATEWAY_ERROR,
                    "The payment gateway could not start this payment. Please try again.");
        }

        // No pay URL: Razorpay's checkout is opened by the browser with this order id and
        // the public key, not by sending anyone to a page of theirs.
        return ProviderOrder.withoutRedirect(order.id());
    }

    private String body(Map<String, Object> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (JacksonException ex) {
            // A Map of a long, two Strings and an int. If this throws, the problem is not
            // the gateway's and retrying will not help.
            throw new IllegalStateException("could not serialise a Razorpay order", ex);
        }
    }

    /**
     * A gateway call with no deadline is a request thread held until the socket gives up,
     * while the payer watches a spinner. Short on purpose: a gateway that cannot be reached
     * in two seconds is not going to be reached.
     */
    private static JdkClientHttpRequestFactory timeouts(RazorpayProperties properties) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        requests.setReadTimeout(properties.readTimeout());
        return requests;
    }

    /**
     * Only the fields this needs. Razorpay sends a dozen more and will add others; binding
     * them all would make a new field in their response a deserialisation failure here.
     */
    record OrderResponse(String id, String status) {
    }

    /**
     * The shape of a webhook body, as far as this cares: the event name, and the payment
     * entity buried under {@code payload.payment.entity}.
     *
     * <p>Nested records rather than a map walk, so a change in their structure is a
     * compile-time concern here instead of a chain of null checks at the call site.
     */
    record Event(String event, Payload payload) {

        String orderId() {
            return payment() == null ? null : payment().orderId();
        }

        String paymentId() {
            return payment() == null ? null : payment().id();
        }

        private PaymentEntity payment() {
            return payload == null || payload.payment() == null
                    ? null
                    : payload.payment().entity();
        }
    }

    record Payload(PaymentWrapper payment) {
    }

    record PaymentWrapper(PaymentEntity entity) {
    }

    record PaymentEntity(String id, @JsonProperty("order_id") String orderId, String status) {
    }
}
