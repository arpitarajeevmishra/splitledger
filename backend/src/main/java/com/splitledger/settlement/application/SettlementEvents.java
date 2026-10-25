package com.splitledger.settlement.application;

import java.util.UUID;

/**
 * What the settlement module announces.
 *
 * <p>Only confirmation. A settlement that is created, marked paid, rejected, cancelled or
 * expired has moved no money and changed no balance, and an event for each of those would
 * be a feed of intentions rather than facts -- while the one thing a consumer has to react
 * to is somebody's debt actually being cleared.
 *
 * @see com.splitledger.expense.application.ExpenseEvents
 */
public final class SettlementEvents {

    public static final String AGGREGATE = "settlement";
    public static final String CONFIRMED = "settlement.confirmed";

    private SettlementEvents() {
    }

    /**
     * @param method the payment method's name rather than the enum, so that adding or
     *     renaming one is not a change to a published payload
     * @param confirmedBy whoever said it had arrived, and <b>null when a gateway did</b>.
     *     A consumer needs this to know who not to tell: the person who confirmed a
     *     payment does not want to be notified that it was confirmed. Null means nobody
     *     acted, so it is news to both parties.
     */
    public record Confirmed(UUID settlementId, UUID groupId, UUID fromUser, UUID toUser,
            long amountPaise, String method, UUID confirmedBy) {
    }
}
