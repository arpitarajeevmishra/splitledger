package com.splitledger.expense.api;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.idempotency.IdempotencyService;
import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.expense.api.dto.CreateExpenseRequest;
import com.splitledger.expense.api.dto.ExpenseResponse;
import com.splitledger.expense.api.dto.ExpenseSummaryResponse;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.Expense;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExpenseController {

    /** idempotency_keys.idem_key is VARCHAR(100). */
    private static final int MAX_KEY_LENGTH = 100;

    private final ExpenseService expenses;
    private final IdempotencyService idempotency;

    ExpenseController(ExpenseService expenses, IdempotencyService idempotency) {
        this.expenses = expenses;
        this.idempotency = idempotency;
    }

    /**
     * Record an expense.
     *
     * <p>Send an {@code Idempotency-Key} and the request becomes safe to retry: a repeat
     * with the same body returns the first response and creates nothing, and the same key
     * with a <em>different</em> body is refused rather than silently answered with the
     * earlier result.
     *
     * <p>The header is optional, as it is at most payment APIs, because requiring it would
     * break every client that does not send one. A caller that omits it gets at-least-once
     * behaviour and a duplicate on retry -- which is worth saying plainly rather than
     * implying the endpoint is safe by default.
     */
    @PostMapping("/groups/{groupId}/expenses")
    public ResponseEntity<ExpenseResponse> create(Authentication authentication,
            @PathVariable UUID groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateExpenseRequest request) {
        UUID actorId = AuthenticatedUser.require(authentication);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(record(groupId, actorId, request));
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            // The column is VARCHAR(100); a longer key would fail at insert with a
            // database error rather than a clear one.
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }

        IdempotencyService.Result<ExpenseResponse> result = idempotency.runOnce(
                actorId, idempotencyKey, request, HttpStatus.CREATED.value(),
                () -> record(groupId, actorId, request), ExpenseResponse.class);

        // A replay repeats the original status, so a retried creation still reads as 201
        // rather than turning into a 200 the client never expected.
        return ResponseEntity.status(result.httpStatus()).body(result.value());
    }

    private ExpenseResponse record(UUID groupId, UUID actorId, CreateExpenseRequest request) {
        List<ExpenseService.Participant> participants = request.participants().stream()
                .map(input -> new ExpenseService.Participant(input.userId(), input.value()))
                .toList();

        return ExpenseResponse.of(expenses.record(groupId, actorId, request.description(),
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

    /**
     * Void an expense. Its postings are reversed, so the group's balances return to
     * exactly where they were.
     *
     * <p>{@code If-Match} carries the version the client last read. One convention for
     * both this and PUT, rather than a header here and a body field there (finding C8):
     * two ways of saying the same thing is how one of them ends up unimplemented.
     */
    @DeleteMapping("/expenses/{expenseId}")
    public ExpenseResponse voidExpense(Authentication authentication,
            @PathVariable UUID expenseId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        Expense voided = expenses.voidExpense(expenseId,
                AuthenticatedUser.require(authentication), parseVersion(ifMatch));
        return ExpenseResponse.of(new ExpenseService.Recorded(voided, List.of()));
    }

    /**
     * Replace an expense with a corrected one.
     *
     * <p>Returns the <em>new</em> expense; the original is voided and keeps its id, so a
     * client holding the old one can still fetch it and see what it used to say.
     */
    @PutMapping("/expenses/{expenseId}")
    public ExpenseResponse replace(Authentication authentication, @PathVariable UUID expenseId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody CreateExpenseRequest request) {
        List<ExpenseService.Participant> participants = request.participants().stream()
                .map(input -> new ExpenseService.Participant(input.userId(), input.value()))
                .toList();

        return ExpenseResponse.of(expenses.replace(expenseId,
                AuthenticatedUser.require(authentication), parseVersion(ifMatch),
                request.description(), request.amountPaise(), request.paidBy(),
                request.expenseDate(), request.splitType(), participants));
    }

    /**
     * An ETag is conventionally quoted and may be weak, so {@code "3"} and {@code W/"3"}
     * are accepted alongside a bare {@code 3}. Anything else is the client's mistake and
     * is refused rather than guessed at -- treating an unparseable version as 0 would
     * silently overwrite someone's edit.
     */
    private static long parseVersion(String ifMatch) {
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "If-Match must be the version you last read");
        }
    }

    @GetMapping("/expenses/{expenseId}")
    public ExpenseResponse detail(Authentication authentication, @PathVariable UUID expenseId) {
        return ExpenseResponse.of(
                expenses.detail(expenseId, AuthenticatedUser.require(authentication)));
    }
}
