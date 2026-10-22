package com.splitledger.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ExpenseSplitId implements Serializable {

    @Column(name = "expense_id", nullable = false, updatable = false)
    private UUID expenseId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    protected ExpenseSplitId() {
        // for JPA
    }

    public ExpenseSplitId(UUID expenseId, UUID userId) {
        this.expenseId = expenseId;
        this.userId = userId;
    }

    public UUID expenseId() {
        return expenseId;
    }

    public UUID userId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExpenseSplitId id
                && Objects.equals(id.expenseId, expenseId)
                && Objects.equals(id.userId, userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expenseId, userId);
    }
}
