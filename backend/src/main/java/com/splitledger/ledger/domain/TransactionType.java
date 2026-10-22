package com.splitledger.ledger.domain;

/**
 * Matches the CHECK constraint on ledger_transactions.type.
 *
 * <p>These name the kinds of business fact that produce postings. They are labels, not a
 * dependency: the ledger posts balanced transactions and must not know what an expense or
 * a settlement actually is (ADR-001, enforced by ArchUnit).
 */
public enum TransactionType {
    EXPENSE,
    SETTLEMENT,
    REVERSAL
}
