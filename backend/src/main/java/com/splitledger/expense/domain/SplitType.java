package com.splitledger.expense.domain;

/** Matches the CHECK constraint on expenses.split_type. */
public enum SplitType {
    EQUAL,
    EXACT,
    PERCENT,
    SHARES
}
