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
 * <p>{@code movesRealMoney} is the one that is not about product rules at all: it says
 * whether anything outside this application is actually involved. Every method but the
 * sandbox takes money from a real person. The sandbox takes nothing from anyone, and the
 * difference decides whether a confirmation reporting "it is paid" is a fact that has
 * already happened or a story the demo is telling itself (SL-604).
 *
 * <p>The minimum is here for the same reason as the TTL: it is a fact about the method
 * that both modules have to name. Putting it on the provider instead would mean no floor
 * at all wherever the gateway's keys are absent -- which is every demo deployment, and
 * exactly where someone is most likely to try settling four rupees forty paise.
 *
 * <p>The TTLs differ because the thing being waited on differs. A checkout session is a
 * browser tab someone either finishes or abandons within minutes; a cash handover waits on
 * two people meeting and one of them remembering to confirm. Giving both the same deadline
 * would either pin a debt behind an abandoned tab or expire a real payment someone was
 * still arranging.
 */
public enum PaymentMethod {

    /**
     * A checkout session: abandoned carts must not pin a debt (LLD 7.1). Razorpay will not
     * take less than a rupee, so neither will we (LLD 7.4, finding C5).
     */
    RAZORPAY(Duration.ofMinutes(15), true, 100L, true),

    /**
     * Stands in for the gateway in the demo, so it keeps the same shape -- including the
     * floor, or the demo would accept amounts the thing it stands in for refuses.
     */
    SANDBOX(Duration.ofMinutes(15), true, 100L, false),

    /** A human opens an app, and the receiver has to confirm it arrived. */
    UPI_INTENT(Duration.ofDays(7), false, 0L, true),

    CASH(Duration.ofDays(7), false, 0L, true);

    private final Duration ttl;
    private final boolean gateway;
    private final long minimumPaise;
    private final boolean movesRealMoney;

    PaymentMethod(Duration ttl, boolean gateway, long minimumPaise, boolean movesRealMoney) {
        this.ttl = ttl;
        this.gateway = gateway;
        this.minimumPaise = minimumPaise;
        this.movesRealMoney = movesRealMoney;
    }

    public Duration ttl() {
        return ttl;
    }

    /** The smallest payment this method will take. Zero where nothing external decides. */
    public long minimumPaise() {
        return minimumPaise;
    }

    /**
     * Whether paying this way actually takes money from someone.
     *
     * <p>True for everything real, including cash in a room. False only for the sandbox,
     * which settles itself with nothing behind it.
     *
     * <p>Used where the difference changes what this application may do about a payment it
     * is told has happened: money that has really gone cannot be refused back into
     * existence, so a report of it is recorded whatever the balance now says. A sandbox
     * confirmation is not evidence of anything and stays subject to the ordinary
     * net-balance rules (ADR-010, as narrowed in SL-604).
     */
    public boolean movesRealMoney() {
        return movesRealMoney;
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
