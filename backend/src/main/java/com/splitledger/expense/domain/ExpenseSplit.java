package com.splitledger.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * What one participant owes for one expense.
 *
 * <p>{@code inputValue} keeps what was actually entered -- exact paise, basis points or a
 * share count -- beside the computed {@code owedPaise}. Without it a 33/33/34 split is
 * indistinguishable from someone having typed those three numbers by hand, and a
 * correction could not reproduce the original intent.
 */
@Entity
@Table(name = "expense_splits")
public class ExpenseSplit {

    @EmbeddedId
    private ExpenseSplitId id;

    @Column(name = "owed_paise", nullable = false, updatable = false)
    private long owedPaise;

    @Column(name = "input_value", updatable = false)
    private Long inputValue;

    protected ExpenseSplit() {
        // for JPA
    }

    private ExpenseSplit(ExpenseSplitId id, long owedPaise, Long inputValue) {
        this.id = id;
        this.owedPaise = owedPaise;
        this.inputValue = inputValue;
    }

    public static ExpenseSplit of(UUID expenseId, UUID userId, long owedPaise, Long inputValue) {
        return new ExpenseSplit(new ExpenseSplitId(expenseId, userId), owedPaise, inputValue);
    }

    public UUID expenseId() {
        return id.expenseId();
    }

    public UUID userId() {
        return id.userId();
    }

    public long owedPaise() {
        return owedPaise;
    }

    public Long inputValue() {
        return inputValue;
    }
}
