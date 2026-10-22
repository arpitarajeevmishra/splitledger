package com.splitledger.common.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and releases the claim row in its own transaction.
 *
 * <p>A separate bean, not a method on {@link IdempotencyService}, because
 * {@code REQUIRES_NEW} is applied by a proxy and a call from inside the same object never
 * reaches it -- the annotation would be silently ignored and the whole design would
 * collapse back into the bug finding C1 describes. Learned the hard way on
 * {@code RefreshTokenChainRevoker}.
 *
 * <p>Why a separate transaction at all: if the claim were inserted in the same transaction
 * as the business logic, a concurrent duplicate could never see IN_PROGRESS. It would
 * block on the primary key until commit and then fail on a key violation, so the
 * advertised 409 would be unreachable -- which is precisely finding C1.
 */
@Component
public class IdempotencyClaims {

    private final IdempotencyRepository records;

    IdempotencyClaims(IdempotencyRepository records) {
        this.records = records;
    }

    /**
     * Insert the claim and commit it immediately.
     *
     * @return true if this caller claimed the key, false if someone already holds it --
     *     the caller then re-reads to decide whether this is a replay, a conflict, or a
     *     request still in flight
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID userId, String key, String requestHash, Instant now) {
        return records.insertClaim(userId, key, requestHash, now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(UUID userId, String key) {
        return records.findById(new IdempotencyRecordId(userId, key));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID userId, String key, int httpStatus, String body) {
        records.findById(new IdempotencyRecordId(userId, key))
                .ifPresent(record -> {
                    record.complete(httpStatus, body);
                    records.saveAndFlush(record);
                });
    }

    /**
     * Drop the claim so a failed request can be retried at once.
     *
     * <p>The LLD leaves a crashed claim for the purge job to reclaim after ten minutes.
     * That is right for a process that died, but wrong for work that simply failed and
     * threw: the client would be locked out of its own key for ten minutes over a
     * validation error. Releasing on a clean failure keeps the purge job for what it is
     * actually needed for.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID userId, String key) {
        records.deleteById(new IdempotencyRecordId(userId, key));
    }
}
