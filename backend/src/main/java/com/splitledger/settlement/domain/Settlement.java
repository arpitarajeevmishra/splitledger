package com.splitledger.settlement.domain;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One person paying another, and everything that can happen to that attempt.
 *
 * <p>Every transition lives here rather than in a service, so there is exactly one place
 * that knows which moves are legal. A status field mutated from three different services
 * is a state machine nobody can read, and the illegal transition always turns out to be
 * in the service someone forgot about.
 *
 * <p>Two rules are worth stating out loud because they look like mistakes:
 *
 * <ul>
 *   <li><b>EXPIRED is not terminal.</b> A gateway can capture a payment after the sweeper
 *       has expired the row. When that happens the money is real and the bookkeeping is
 *       wrong, so the money wins.</li>
 *   <li><b>Confirming an already-confirmed settlement succeeds.</b> It is a no-op, not an
 *       error: a retried webhook and a receiver tapping twice are the same event arriving
 *       again, and failing the second one would make a correct client look broken.</li>
 * </ul>
 */
@Entity
@Table(name = "settlements")
public class Settlement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "from_user", nullable = false, updatable = false)
    private UUID fromUser;

    @Column(name = "to_user", nullable = false, updatable = false)
    private UUID toUser;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false, length = 12)
    private SettlementMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private SettlementStatus status;

    @Column(name = "gateway_order_id", length = 64)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 64)
    private String gatewayPaymentId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Settlement() {
        // for JPA
    }

    private Settlement(UUID id, UUID groupId, UUID fromUser, UUID toUser, long amountPaise,
            SettlementMethod method, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.groupId = groupId;
        this.fromUser = fromUser;
        this.toUser = toUser;
        this.amountPaise = amountPaise;
        this.method = method;
        this.status = SettlementStatus.INITIATED;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** The expiry deadline is fixed at creation, from the method's own TTL (finding C17). */
    public static Settlement initiate(UUID groupId, UUID fromUser, UUID toUser, long amountPaise,
            SettlementMethod method, Instant now) {
        if (fromUser.equals(toUser)) {
            // The database forbids it too; catching it here gives a readable error rather
            // than a constraint violation.
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "You cannot settle with yourself");
        }
        if (amountPaise <= 0L) {
            throw new ApiException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "A settlement must be for more than zero");
        }
        return new Settlement(UUID.randomUUID(), groupId, fromUser, toUser, amountPaise, method,
                now, now.plus(method.ttl()));
    }

    /** An order was created, or the payer says they have paid. */
    public void markPending(Instant now) {
        require(status == SettlementStatus.INITIATED, "Only a new settlement can be started");
        this.status = SettlementStatus.PENDING;
    }

    /**
     * The money arrived.
     *
     * <p>Allowed from EXPIRED as well as PENDING: a late capture means the payment really
     * happened, and refusing it would leave a settled debt looking unpaid.
     */
    public void confirm(Instant now) {
        if (status == SettlementStatus.CONFIRMED) {
            // Idempotent on purpose -- a retried webhook is the same event, not a second one.
            return;
        }
        require(status == SettlementStatus.PENDING || status == SettlementStatus.EXPIRED,
                "Only a settlement awaiting payment can be confirmed");
        this.status = SettlementStatus.CONFIRMED;
        this.confirmedAt = now;
    }

    /** The receiver says the money never arrived. */
    public void reject(Instant now) {
        require(status == SettlementStatus.PENDING,
                "Only a settlement awaiting confirmation can be rejected");
        this.status = SettlementStatus.FAILED;
    }

    /**
     * The payer calls it off.
     *
     * <p>Allowed before anything was attempted, and afterwards only when no external
     * system owns the outcome: once a gateway order exists the money may already be
     * moving, and the gateway decides what happened, not us.
     */
    public void cancel(Instant now) {
        if (status == SettlementStatus.INITIATED) {
            this.status = SettlementStatus.CANCELLED;
            return;
        }
        require(status == SettlementStatus.PENDING && !method.isGateway(),
                method.isGateway()
                        ? "A payment already with the gateway cannot be cancelled here"
                        : "Only a settlement awaiting payment can be cancelled");
        this.status = SettlementStatus.CANCELLED;
    }

    /**
     * The deadline passed. Sweeps INITIATED as well as PENDING (finding C17): a settlement
     * abandoned before any order would otherwise sit in the list forever.
     */
    public void expire(Instant now) {
        require(status == SettlementStatus.INITIATED || status == SettlementStatus.PENDING,
                "Only an unfinished settlement can expire");
        this.status = SettlementStatus.EXPIRED;
    }

    public void recordGatewayOrder(String orderId) {
        this.gatewayOrderId = orderId;
    }

    public void recordGatewayPayment(String paymentId) {
        this.gatewayPaymentId = paymentId;
    }

    public boolean hasExpiredBy(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    private static void require(boolean legal, String message) {
        if (!legal) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, message);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID groupId() {
        return groupId;
    }

    public UUID fromUser() {
        return fromUser;
    }

    public UUID toUser() {
        return toUser;
    }

    public long amountPaise() {
        return amountPaise;
    }

    public SettlementMethod method() {
        return method;
    }

    public SettlementStatus status() {
        return status;
    }

    public String gatewayOrderId() {
        return gatewayOrderId;
    }

    public String gatewayPaymentId() {
        return gatewayPaymentId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public long version() {
        return version;
    }
}
