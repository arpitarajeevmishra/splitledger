package com.splitledger.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.common.webhook.WebhookEventRepository;
import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.payment.port.ProviderEvent;
import com.splitledger.payment.port.ProviderWebhook;
import com.splitledger.settlement.infra.SettlementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * What the webhook endpoint reports about itself (SL-704).
 *
 * <p>SL-704 names {@code webhook_duplicates_total}. This is that series, carried as a tag
 * on one counter rather than flattened into the metric name: five counters named after
 * their outcomes cannot be summed, so "how many deliveries arrived" and "what fraction were
 * duplicates" would each need a hardcoded list of every outcome that exists -- a list that
 * goes stale the first time somebody adds one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayWebhookMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Mock
    private SettlementRepository settlements;
    @Mock
    private WebhookEventRepository events;
    @Mock
    private SettlementService settlementService;
    @Mock
    private PaymentProvider provider;
    @Mock
    private ProviderEvent event;

    private final MeterRegistry registry = new SimpleMeterRegistry();

    private GatewayWebhookService service(List<PaymentProvider> providers) {
        return new GatewayWebhookService(providers, settlements, events, settlementService,
                registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProviderWebhook delivery() {
        return new ProviderWebhook("{}".getBytes(StandardCharsets.UTF_8),
                Map.of("x-razorpay-signature", "whatever"));
    }

    private double counted(String outcome) {
        var counter = registry.find("splitledger.webhook.deliveries")
                .tag("provider", "RAZORPAY").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void counts_a_delivery_that_failed_verification() {
        // No provider configured for the method, so nothing can verify the signature --
        // the same path a forged or mis-signed delivery takes.
        assertThatThrownBy(() -> service(List.of()).handle(PaymentMethod.RAZORPAY, delivery()))
                .isInstanceOf(ApiException.class);

        // The one a person most wants to see. A rejected delivery is either a
        // misconfigured secret -- in which case no payment will ever confirm by webhook
        // again -- or somebody probing a public POST endpoint. Counting only the outcomes
        // that returned normally would make both invisible.
        assertThat(counted("REJECTED")).isEqualTo(1.0);
    }

    @Test
    void counts_a_redelivery_as_a_duplicate_rather_than_a_problem() {
        when(provider.method()).thenReturn(PaymentMethod.RAZORPAY);
        when(provider.verifiedEvent(any())).thenReturn(Optional.of(event));
        when(event.eventId()).thenReturn("evt_1");
        when(event.type()).thenReturn("payment.captured");
        // Already claimed: the provider retries until it gets a 2xx, and this is how the
        // second one is meant to end.
        when(events.claim(any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(service(List.of(provider)).handle(PaymentMethod.RAZORPAY, delivery()))
                .isEqualTo(GatewayWebhookService.Outcome.DUPLICATE);

        assertThat(counted("DUPLICATE")).isEqualTo(1.0);
        assertThat(counted("REJECTED")).isZero();
    }
}
