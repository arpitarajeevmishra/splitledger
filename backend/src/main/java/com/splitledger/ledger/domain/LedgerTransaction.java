package com.splitledger.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One business fact, recorded once (ADR-003).
 *
 * <p>Nothing here is ever updated or deleted. A mistake is corrected by posting a
 * reversal, which is itself a transaction, so the history of what was believed and when
 * stays readable. That is the whole argument for an immutable ledger: a balance you cannot
 * explain is a balance nobody trusts.
 *
 * <p>{@code sourceId} points at whatever caused this -- an expense id, a settlement id --
 * without the ledger knowing what that thing is. A unique index on (type, source_id)
 * excluding reversals means a retry or a bug cannot post the same fact twice (finding C4).
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 12)
    private TransactionType type;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "reverses_txn_id", updatable = false)
    private UUID reversesTxnId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
        // for JPA
    }

    private LedgerTransaction(UUID id, UUID groupId, TransactionType type, UUID sourceId,
            UUID reversesTxnId, Instant createdAt) {
        this.id = id;
        this.groupId = groupId;
        this.type = type;
        this.sourceId = sourceId;
        this.reversesTxnId = reversesTxnId;
        this.createdAt = createdAt;
    }

    public static LedgerTransaction of(UUID groupId, TransactionType type, UUID sourceId,
            Instant now) {
        return new LedgerTransaction(UUID.randomUUID(), groupId, type, sourceId, null, now);
    }

    /**
     * A reversal keeps the original's {@code sourceId} so both sides of the correction can
     * be found from the thing that caused it, and records which transaction it undoes.
     */
    public static LedgerTransaction reversalOf(LedgerTransaction original, Instant now) {
        return new LedgerTransaction(UUID.randomUUID(), original.groupId,
                TransactionType.REVERSAL, original.sourceId, original.id, now);
    }

    public boolean isReversal() {
        return type == TransactionType.REVERSAL;
    }

    public UUID id() {
        return id;
    }

    public UUID groupId() {
        return groupId;
    }

    public TransactionType type() {
        return type;
    }

    public UUID sourceId() {
        return sourceId;
    }

    public UUID reversesTxnId() {
        return reversesTxnId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
