package com.splitledger.payment.sandbox;

import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.payment.port.PaymentRequest;
import com.splitledger.payment.port.ProviderOrder;
import com.splitledger.common.payment.PaymentMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A gateway that is not there.
 *
 * <p>Exists so the whole settlement path -- create an order, wait, receive confirmation
 * from something other than the receiver -- can be demonstrated without an account, a key
 * or a network call. That matters more than it sounds: it is the difference between a demo
 * anyone can run and one that works only where secrets are configured (ADR-013, B4).
 *
 * <p>It reports success after a delay rather than immediately, because instant
 * confirmation would skip PENDING entirely and the UI would never be exercised against the
 * state it will spend most of its time in once a real gateway is involved. A demo that
 * only shows the happy end of a flow hides exactly the states that are hard to get right.
 */
@Component
public class SandboxProvider implements PaymentProvider {

    private final boolean autoConfirm;
    private final Duration delay;
    private final Clock clock;

    @Autowired
    SandboxProvider(@Value("${app.payments.sandbox.auto-confirm-enabled}") boolean autoConfirm,
            @Value("${app.payments.sandbox.auto-confirm-delay}") Duration delay) {
        this(autoConfirm, delay, Clock.systemUTC());
    }

    /** Visible for testing. */
    SandboxProvider(boolean autoConfirm, Duration delay, Clock clock) {
        this.autoConfirm = autoConfirm;
        this.delay = delay;
        this.clock = clock;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.SANDBOX;
    }

    @Override
    public ProviderOrder initiate(PaymentRequest request) {
        // No redirect: there is nowhere to send anyone. The order id is still a real,
        // unique reference, so the rest of the system treats this exactly as it will
        // treat a gateway order.
        return ProviderOrder.withoutRedirect("sandbox_" + UUID.randomUUID());
    }

    @Override
    public Optional<String> completedPaymentId(String orderId, Instant orderedAt) {
        if (!autoConfirm || Instant.now(clock).isBefore(orderedAt.plus(delay))) {
            return Optional.empty();
        }
        return Optional.of(orderId.replaceFirst("^sandbox_", "sandbox_payment_"));
    }
}
