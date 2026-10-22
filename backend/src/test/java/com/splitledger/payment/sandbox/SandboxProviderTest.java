package com.splitledger.payment.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.payment.port.PaymentRequest;
import com.splitledger.payment.port.ProviderOrder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxProviderTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final Duration DELAY = Duration.ofSeconds(10);

    private SandboxProvider at(Instant now) {
        return new SandboxProvider(true, DELAY, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PaymentRequest request() {
        return new PaymentRequest(UUID.randomUUID(), 30_000L, "INR");
    }

    @Test
    void itAnswersForTheSandboxMethod() {
        assertThat(at(NOW).method()).isEqualTo(PaymentMethod.SANDBOX);
    }

    @Test
    void anOrderHasARealReferenceButNowhereToSendAnyone() {
        ProviderOrder order = at(NOW).initiate(request());

        assertThat(order.orderId()).startsWith("sandbox_");
        // Nothing to redirect to -- but the rest of the system treats this exactly as it
        // will treat a gateway order.
        assertThat(order.payUrl()).isNull();
    }

    @Test
    void ordersAreDistinct() {
        SandboxProvider provider = at(NOW);

        assertThat(provider.initiate(request()).orderId())
                .isNotEqualTo(provider.initiate(request()).orderId());
    }

    @Test
    void nothingIsConfirmedBeforeTheDelayHasPassed() {
        String orderId = at(NOW).initiate(request()).orderId();

        // The delay is the point: confirming on the spot would skip PENDING entirely and
        // the UI would never be exercised against it.
        assertThat(at(NOW).completedPaymentId(orderId, NOW)).isEmpty();
        assertThat(at(NOW.plusSeconds(9)).completedPaymentId(orderId, NOW)).isEmpty();
    }

    @Test
    void itConfirmsOnceTheDelayHasElapsed() {
        String orderId = at(NOW).initiate(request()).orderId();

        assertThat(at(NOW.plus(DELAY)).completedPaymentId(orderId, NOW))
                .hasValueSatisfying(paymentId ->
                        assertThat(paymentId).startsWith("sandbox_payment_"));
    }

    @Test
    void itConfirmsNothingWhenSwitchedOff() {
        SandboxProvider off = new SandboxProvider(false, DELAY,
                Clock.fixed(NOW.plus(DELAY), ZoneOffset.UTC));

        // A deployment that wants to drive the flow by hand can turn this off and confirm
        // through the ordinary receiver path instead.
        assertThat(off.completedPaymentId("sandbox_x", NOW)).isEmpty();
    }

    @Test
    void thePaymentIdIsDerivedFromTheOrderSoTheyCanBeMatched() {
        String orderId = "sandbox_abc";

        assertThat(at(NOW.plus(DELAY)).completedPaymentId(orderId, NOW))
                .contains("sandbox_payment_abc");
    }
}
