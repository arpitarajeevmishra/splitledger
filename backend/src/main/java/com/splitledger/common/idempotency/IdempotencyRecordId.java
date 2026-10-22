package com.splitledger.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Keys are scoped per user, so two people may pick the same key without colliding. */
@Embeddable
public class IdempotencyRecordId implements Serializable {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idem_key", nullable = false, updatable = false, length = 100)
    private String idemKey;

    protected IdempotencyRecordId() {
        // for JPA
    }

    public IdempotencyRecordId(UUID userId, String idemKey) {
        this.userId = userId;
        this.idemKey = idemKey;
    }

    public UUID userId() {
        return userId;
    }

    public String idemKey() {
        return idemKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdempotencyRecordId id
                && Objects.equals(id.userId, userId)
                && Objects.equals(id.idemKey, idemKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, idemKey);
    }
}
