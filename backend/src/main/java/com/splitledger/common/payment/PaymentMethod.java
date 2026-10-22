package com.splitledger.common.payment;

import java.time.Duration;

/**
 * How a payment is made, and how long it may sit unanswered.
 *
 * <p>In {@code common} rather than in either feature, because the settlement module and
 * the payment module both need to name these and neither may depend on the other --
 * ArchUnit catches that cycle, and it is right to. Shared vocabulary with no behaviour
 * of its own belongs where both can see it, next to Money.
 *
 * <p>The TTLs differ because the thing being waited on differs. A checkout session is a
 * browser tab someone either finishes or abandons within minutes; a cash handover waits on
 * two people meeting and one of them remembering to confirm. Giving both the same deadline
 * would either pin a debt behind an abandoned tab or expire a real payment someone was
 * still arranging.
 */
public enum PaymentMethod {

    /** A checkout session: abandoned carts must not pin a debt (LLD 7.1). */
    RAZORPAY(Duration.ofMinutes(15), true),

    /** Stands in for the gateway in the demo, so it keeps the same shape. */
    SANDBOX(Duration.ofMinutes(15), true),

    /** A human opens an app, and the receiver has to confirm it arrived. */
    UPI_INTENT(Duration.ofDays(7), false),

    CASH(Duration.ofDays(7), false);

    private final Duration ttl;
    private final boolean gateway;

    PaymentMethod(Duration ttl, boolean gateway) {
        this.ttl = ttl;
        this.gateway = gateway;
    }

    public Duration ttl() {
        return ttl;
    }

    /**
     * Whether an external system owns the outcome.
     *
     * <p>A payer may not cancel a gateway settlement once an order exists: the money may
     * already be moving, and the gateway, not this application, decides what happened.
     */
    public boolean isGateway() {
        return gateway;
    }
}
