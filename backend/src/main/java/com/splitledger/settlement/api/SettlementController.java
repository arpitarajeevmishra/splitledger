package com.splitledger.settlement.api;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.idempotency.IdempotencyService;
import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.settlement.api.dto.CreateSettlementRequest;
import com.splitledger.settlement.api.dto.SettlementResponse;
import com.splitledger.settlement.application.SettlementService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SettlementController {

    /** idempotency_keys.idem_key is VARCHAR(100). */
    private static final int MAX_KEY_LENGTH = 100;

    private final SettlementService settlements;
    private final IdempotencyService idempotency;

    SettlementController(SettlementService settlements, IdempotencyService idempotency) {
        this.settlements = settlements;
        this.idempotency = idempotency;
    }

    /**
     * Record an intent to pay someone in the group.
     *
     * <p>Idempotent when given a key, for the same reason expense creation is: a client
     * whose connection drops cannot tell that from a request that never arrived, and its
     * retry must not record a second payment.
     */
    @PostMapping("/groups/{groupId}/settlements")
    public ResponseEntity<SettlementResponse> create(Authentication authentication,
            @PathVariable UUID groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSettlementRequest request) {
        UUID payerId = AuthenticatedUser.require(authentication);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(record(groupId, payerId, request));
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }

        IdempotencyService.Result<SettlementResponse> result = idempotency.runOnce(
                payerId, idempotencyKey, request, HttpStatus.CREATED.value(),
                () -> record(groupId, payerId, request), SettlementResponse.class);

        return ResponseEntity.status(result.httpStatus()).body(result.value());
    }

    @GetMapping("/groups/{groupId}/settlements")
    public List<SettlementResponse> list(Authentication authentication,
            @PathVariable UUID groupId) {
        return settlements.list(groupId, AuthenticatedUser.require(authentication)).stream()
                .map(SettlementResponse::of)
                .toList();
    }

    /**
     * The payer says they have sent the money.
     *
     * <p>For cash and UPI intent only in practice: a gateway settlement moves to pending
     * when its order is created, not because someone said so.
     */
    @PostMapping("/settlements/{settlementId}/mark-paid")
    public SettlementResponse markPaid(Authentication authentication,
            @PathVariable UUID settlementId) {
        return SettlementResponse.of(
                settlements.markPaid(settlementId, AuthenticatedUser.require(authentication)));
    }

    /**
     * The receiver says the money arrived. This is what posts it to the ledger.
     *
     * <p>Confirming twice succeeds and posts once -- a second tap and a retried request
     * are the same event.
     */
    @PostMapping("/settlements/{settlementId}/confirm")
    public SettlementResponse confirm(Authentication authentication,
            @PathVariable UUID settlementId) {
        return SettlementResponse.of(
                settlements.confirm(settlementId, AuthenticatedUser.require(authentication)));
    }

    /** The receiver says it never arrived. */
    @PostMapping("/settlements/{settlementId}/reject")
    public SettlementResponse reject(Authentication authentication,
            @PathVariable UUID settlementId) {
        return SettlementResponse.of(
                settlements.reject(settlementId, AuthenticatedUser.require(authentication)));
    }

    /** The payer calls it off. */
    @PostMapping("/settlements/{settlementId}/cancel")
    public SettlementResponse cancel(Authentication authentication,
            @PathVariable UUID settlementId) {
        return SettlementResponse.of(
                settlements.cancel(settlementId, AuthenticatedUser.require(authentication)));
    }

    /** Single settlement, for a client polling one it just created. */
    @GetMapping("/settlements/{settlementId}")
    public SettlementResponse detail(Authentication authentication,
            @PathVariable UUID settlementId) {
        return SettlementResponse.of(
                settlements.detail(settlementId, AuthenticatedUser.require(authentication)));
    }

    private SettlementResponse record(UUID groupId, UUID payerId,
            CreateSettlementRequest request) {
        return SettlementResponse.of(settlements.create(groupId, payerId, request.toUser(),
                request.amountPaise(), request.method()));
    }
}
