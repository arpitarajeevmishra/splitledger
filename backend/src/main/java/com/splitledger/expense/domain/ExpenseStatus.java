package com.splitledger.expense.domain;

/** Matches the CHECK constraint on expenses.status. */
public enum ExpenseStatus {
    ACTIVE,
    VOIDED
}
