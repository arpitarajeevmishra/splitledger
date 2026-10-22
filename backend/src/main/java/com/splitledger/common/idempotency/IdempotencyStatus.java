package com.splitledger.common.idempotency;

/** Matches the CHECK constraint on idempotency_keys.status. */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
