package com.splitledger.activity.domain;

/**
 * What happened. The first three move balances; the last two do not, and are here because
 * a feed that explained only the money would leave "how did this person get here"
 * unanswered.
 */
public enum ActivityKind {
    EXPENSE_ADDED,
    EXPENSE_VOIDED,
    SETTLEMENT_CONFIRMED,
    MEMBER_JOINED,
    MEMBER_LEFT
}
