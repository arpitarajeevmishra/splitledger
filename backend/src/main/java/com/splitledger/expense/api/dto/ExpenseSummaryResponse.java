package com.splitledger.expense.api.dto;

import com.splitledger.expense.domain.Expense;
import com.splitledger.expense.domain.ExpenseStatus;
import com.splitledger.expense.domain.SplitType;
import java.time.LocalDate;
import java.util.UUID;

/** A row in the group's expense list. Splits are fetched with the detail, not the list. */
public record ExpenseSummaryResponse(
        UUID id,
        String description,
        long amountPaise,
        UUID paidBy,
        LocalDate expenseDate,
        SplitType splitType,
        ExpenseStatus status,
        long version) {

    public static ExpenseSummaryResponse of(Expense expense) {
        return new ExpenseSummaryResponse(expense.id(), expense.description(),
                expense.amountPaise(), expense.paidBy(), expense.expenseDate(),
                expense.splitType(), expense.status(), expense.version());
    }
}
