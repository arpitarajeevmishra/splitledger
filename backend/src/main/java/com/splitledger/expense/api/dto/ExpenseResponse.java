package com.splitledger.expense.api.dto;

import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.ExpenseSplit;
import com.splitledger.expense.domain.ExpenseStatus;
import com.splitledger.expense.domain.SplitType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExpenseResponse(
        UUID id,
        UUID groupId,
        String description,
        long amountPaise,
        String currency,
        UUID paidBy,
        LocalDate expenseDate,
        SplitType splitType,
        ExpenseStatus status,
        Instant createdAt,
        long version,
        List<SplitResponse> splits) {

    /**
     * @param inputValue what was entered -- paise, basis points or shares. Returned so a
     *     client editing an expense can show the original intent rather than the computed
     *     result, which is what a 33/33/34 split would otherwise look like.
     */
    public record SplitResponse(UUID userId, long owedPaise, Long inputValue) {
    }

    public static ExpenseResponse of(ExpenseService.Recorded recorded) {
        List<SplitResponse> splits = recorded.splits().stream()
                .map(ExpenseResponse::splitOf)
                .toList();
        return new ExpenseResponse(
                recorded.expense().id(),
                recorded.expense().groupId(),
                recorded.expense().description(),
                recorded.expense().amountPaise(),
                recorded.expense().currency(),
                recorded.expense().paidBy(),
                recorded.expense().expenseDate(),
                recorded.expense().splitType(),
                recorded.expense().status(),
                recorded.expense().createdAt(),
                recorded.expense().version(),
                splits);
    }

    private static SplitResponse splitOf(ExpenseSplit split) {
        return new SplitResponse(split.userId(), split.owedPaise(), split.inputValue());
    }
}
