package com.splitledger.expense.api.dto;

import com.splitledger.expense.domain.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param amountPaise integer paise, never a decimal (ADR-002). The ceiling is the same
 *     one the DB CHECK enforces: it is what keeps {@code total * weight} inside a long
 *     during allocation, so it is checked here too rather than only at the database
 *     (finding C2).
 * @param participants at most 50, which bounds the allocation loop and the postings
 *     written in one transaction (M6)
 */
public record CreateExpenseRequest(
        @NotBlank @Size(max = 200) String description,
        @Min(1) @Max(100_000_000L) long amountPaise,
        @NotNull UUID paidBy,
        @NotNull LocalDate expenseDate,
        @NotNull SplitType splitType,
        @NotEmpty @Size(max = 50) @Valid List<ParticipantInput> participants) {

    /**
     * @param value ignored for EQUAL; paise for EXACT, basis points for PERCENT, a share
     *     count for SHARES. Nullable because EQUAL genuinely has nothing to say here, and
     *     a required-but-ignored field invites callers to send nonsense.
     */
    public record ParticipantInput(@NotNull UUID userId, Long value) {
    }
}
