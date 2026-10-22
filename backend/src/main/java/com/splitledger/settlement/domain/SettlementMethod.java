package com.splitledger.settlement.domain;

import java.time.Duration;

/**
 * How a settlement is paid, and how long it may sit unanswered.
 *
 * <p>The TTLs differ because the thing being waited on differs. A checkout session is a
 * browser tab someone either finishes or abandons within minutes; a cash handover waits on
 * two people meeting and one of them remembering to confirm. Giving both the same deadline
 * would either pin a debt behind an abandoned tab or expire a real payment someone was
 * still arranging.
 */
public enum SettlementMethod {

    /** A checkout session: abandoned carts must not pin a debt (LLD 7.1). */
    RAZORPAY(Duration.ofMinutes(15), true),

    /** Stands in for the gateway in the demo, so it keeps the same shape. */
    SANDBOX(Duration.ofMinutes(15), true),

    /** A human opens an app, and the receiver has to confirm it arrived. */
    UPI_INTENT(Duration.ofDays(7), false),

    CASH(Duration.ofDays(7), false);

    private final Duration ttl;
    private final boolean gateway;

    SettlementMethod(Duration ttl, boolean gateway) {
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
