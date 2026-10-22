package com.splitledger.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A claim on one (user, key) pair, and the response that key produced.
 *
 * <p>The row is written <em>before</em> the work starts and completed afterwards, which is
 * what makes a duplicate request distinguishable from a first one. The stored response is
 * what a replay returns, so a client that retries after a dropped connection gets the
 * answer it missed rather than a second expense.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @EmbeddedId
    private IdempotencyRecordId id;

    /**
     * SHA-256 of the request body. The same key with a different body is a client bug --
     * usually a key being reused rather than regenerated -- and answering it with the
     * first request's response would be worse than refusing.
     */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    private IdempotencyRecord(IdempotencyRecordId id, String requestHash, Instant createdAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = createdAt;
    }

    public static IdempotencyRecord claim(UUID userId, String idemKey, String requestHash,
            Instant now) {
        return new IdempotencyRecord(new IdempotencyRecordId(userId, idemKey), requestHash, now);
    }

    public void complete(int httpStatus, String body) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = httpStatus;
        this.responseBody = body;
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean matches(String candidateHash) {
        return requestHash.equals(candidateHash);
    }

    public IdempotencyRecordId id() {
        return id;
    }

    public IdempotencyStatus status() {
        return status;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
