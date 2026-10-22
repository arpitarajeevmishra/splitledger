package com.splitledger.settlement.domain;

/** Matches the CHECK constraint on settlements.status. */
public enum SettlementStatus {

    /** Created, but nothing has been attempted yet. */
    INITIATED,

    /** An order exists, or the payer says they have paid; waiting on confirmation. */
    PENDING,

    CONFIRMED,
    FAILED,
    CANCELLED,
    EXPIRED;

    /**
     * EXPIRED is deliberately not terminal: a gateway can capture a payment after the TTL
     * has swept the row, and when it does, the money is real. Money truth wins over our
     * bookkeeping (LLD 7.1).
     */
    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED || this == CANCELLED;
    }
}
