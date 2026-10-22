package com.splitledger.expense.api;

import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.expense.api.dto.CreateExpenseRequest;
import com.splitledger.expense.api.dto.ExpenseResponse;
import com.splitledger.expense.api.dto.ExpenseSummaryResponse;
import com.splitledger.expense.application.ExpenseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExpenseController {

    private final ExpenseService expenses;

    ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    /**
     * Record an expense.
     *
     * <p>Not yet idempotent -- {@code Idempotency-Key} arrives in SL-304. Until then a
     * retried request records a second expense, which is why the header is not quietly
     * accepted and ignored: a client that sends one would reasonably assume it worked.
     */
    @PostMapping("/groups/{groupId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse create(Authentication authentication, @PathVariable UUID groupId,
            @Valid @RequestBody CreateExpenseRequest request) {
        List<ExpenseService.Participant> participants = request.participants().stream()
                .map(input -> new ExpenseService.Participant(input.userId(), input.value()))
                .toList();

        return ExpenseResponse.of(expenses.record(groupId,
                AuthenticatedUser.require(authentication), request.description(),
                request.amountPaise(), request.paidBy(), request.expenseDate(),
                request.splitType(), participants));
    }

    @GetMapping("/groups/{groupId}/expenses")
    public List<ExpenseSummaryResponse> list(Authentication authentication,
            @PathVariable UUID groupId) {
        return expenses.list(groupId, AuthenticatedUser.require(authentication)).stream()
                .map(ExpenseSummaryResponse::of)
                .toList();
    }

    @GetMapping("/expenses/{expenseId}")
    public ExpenseResponse detail(Authentication authentication, @PathVariable UUID expenseId) {
        return ExpenseResponse.of(
                expenses.detail(expenseId, AuthenticatedUser.require(authentication)));
    }
}
