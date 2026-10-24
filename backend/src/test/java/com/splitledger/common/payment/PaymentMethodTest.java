package com.splitledger.common.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentMethodTest {

    @Test
    void theGatewayMethodsRefuseUnderARupee() {
        // Razorpay's own floor (LLD 7.4, finding C5), said by us so the payer hears it when
        // they pick the method rather than as an opaque gateway failure at the end.
        assertThat(PaymentMethod.RAZORPAY.minimumPaise()).isEqualTo(100L);
        // The sandbox stands in for the gateway, so it has to refuse what the gateway
        // refuses. A demo that accepts 40 paise teaches the wrong thing about the flow it
        // exists to demonstrate.
        assertThat(PaymentMethod.SANDBOX.minimumPaise()).isEqualTo(100L);
    }

    @Test
    void theHumanMethodsHaveNoFloor() {
        // Nothing external decides, so there is nothing to refuse: forty paise in cash is
        // forty paise in cash.
        assertThat(PaymentMethod.CASH.minimumPaise()).isZero();
        assertThat(PaymentMethod.UPI_INTENT.minimumPaise()).isZero();
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void everyMethodHasAFloorAndADeadlineThatMakeSense(PaymentMethod method) {
        // A negative floor would reject everything while claiming a limit below zero, and a
        // method whose TTL is zero expires the instant it is created.
        assertThat(method.minimumPaise()).isNotNegative();
        assertThat(method.ttl()).isGreaterThan(Duration.ZERO);
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void onlyGatewayMethodsHaveAFloor(PaymentMethod method) {
        // The floor exists because something outside this application sets it. If a
        // non-gateway method ever grows one, that reasoning has changed and should be
        // argued for rather than inherited.
        assertThat(method.minimumPaise() > 0L).isEqualTo(method.isGateway());
    }
}
