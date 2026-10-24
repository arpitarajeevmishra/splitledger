package com.splitledger.settlement.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.common.webhook.WebhookEventRepository;
import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.payment.port.ProviderEvent;
import com.splitledger.payment.port.ProviderWebhook;
import com.splitledger.settlement.domain.Settlement;
import com.splitledger.settlement.infra.SettlementRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook deliveries from a payment gateway (SL-603).
 *
 * <p>A webhook is the authority on whether money moved: it arrives from the provider's own
 * servers whether or not the payer's browser ever came back, which is why the client
 * callback (SL-602) is a convenience and this is the truth. It is also an unauthenticated
 * POST on the public internet, so every delivery is a forgery until its signature says
 * otherwise.
 *
 * <p>The order of work is the design, and each step is there because the step before it is
 * not enough:
 *
 * <ol>
 *   <li><b>Verify</b>, asking the provider, over the raw bytes. Nothing else happens first,
 *       including writing the delivery down -- an unsigned POST must not be able to fill
 *       this table.</li>
 *   <li><b>Claim</b> {@code (provider, event_id)} with an insert that collides. Razorpay
 *       retries deliveries, and twenty copies of one capture must post to the ledger once.
 *       The database decides who won, not a read-then-write in this process.</li>
 *   <li><b>Resolve</b> the settlement by the order id this application stored, never by
 *       anything in the payload that claims to be a settlement id.</li>
 *   <li><b>Confirm</b>, in the same transaction as the claim, so a failure anywhere leaves
 *       both the claim and the posting undone and the provider's next retry can try
 *       again.</li>
 * </ol>
 */
@Service
public class GatewayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebhookService.class);

    private final List<PaymentProvider> providers;
    private final SettlementRepository settlements;
    private final WebhookEventRepository events;
    private final SettlementService settlementService;
    private final Clock clock;

    @Autowired
    GatewayWebhookService(List<PaymentProvider> providers, SettlementRepository settlements,
            WebhookEventRepository events, SettlementService settlementService) {
        this(providers, settlements, events, settlementService, Clock.systemUTC());
    }

    /** Visible for testing. */
    GatewayWebhookService(List<PaymentProvider> providers, SettlementRepository settlements,
            WebhookEventRepository events, SettlementService settlementService, Clock clock) {
        this.providers = providers;
        this.settlements = settlements;
        this.events = events;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    /** What the endpoint should answer, and why, without leaking it to the caller. */
    public enum Outcome {

        /** Verified, claimed, and the settlement confirmed. */
        PROCESSED,

        /** Verified, and already handled: a retry or a duplicate delivery. */
        DUPLICATE,

        /** Verified, and nothing here to act on -- an event type or entity we do not use. */
        IGNORED
    }

    /**
     * @throws ApiException with {@code PAYMENT_SIGNATURE_INVALID} when the delivery
     *     cannot be verified. The provider is told 400 so it retries; nothing is written.
     */
    @Transactional
    public Outcome handle(PaymentMethod method, ProviderWebhook webhook) {
        ProviderEvent event = providerFor(method)
                .flatMap(provider -> provider.verifiedEvent(webhook))
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                        "That delivery could not be verified"));

        Instant now = Instant.now(clock);
        // The body is stored as it arrived, which is also the only form the signature was
        // ever over. A re-serialised copy would be a different document.
        String payload = new String(webhook.rawBody(), StandardCharsets.UTF_8);

        if (events.claim(UUID.randomUUID(), method.name(), event.eventId(),
                event.type() == null ? "unknown" : event.type(), payload, now) == 0) {
            // Someone already has this delivery. Not an error: Razorpay retries until it
            // gets a 2xx, and this is how the second one is meant to end.
            return Outcome.DUPLICATE;
        }

        if (!event.isPaymentCaptured() || event.orderId() == null) {
            // Authorised-but-not-captured, refunds, settlements of their own -- verified
            // and none of this application's business yet. Recorded, so the next person to
            // ask "did anything arrive" has an answer. payment.failed becomes an attempt
            // in SL-604.
            events.finish(method.name(), event.eventId(), "IGNORED", now);
            return Outcome.IGNORED;
        }

        Optional<Settlement> settlement = settlements.findByGatewayOrderId(event.orderId());
        if (settlement.isEmpty()) {
            // A genuine event about an order this application has no record of: an orphan
            // from a failed transaction (O-13), or another deployment sharing the account.
            // Claimed, so retries stop, and marked rather than silently dropped.
            log.warn("A verified {} event named order {}, which no settlement holds",
                    method, event.orderId());
            events.finish(method.name(), event.eventId(), "IGNORED", now);
            return Outcome.IGNORED;
        }

        // Same transaction as the claim, deliberately. If this throws, the claim goes with
        // it and the provider's next retry gets a fresh attempt -- whereas committing the
        // claim first would turn one failed delivery into a payment nobody ever retries.
        settlementService.confirmFromProvider(settlement.get().id(), event.paymentId());
        events.finish(method.name(), event.eventId(), "PROCESSED", now);
        return Outcome.PROCESSED;
    }

    private Optional<PaymentProvider> providerFor(PaymentMethod method) {
        return providers.stream().filter(provider -> provider.method() == method).findFirst();
    }
}
