package com.splitledger.common.idempotency;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository
        extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {

    /**
     * Insert the claim, or report that someone already holds it.
     *
     * <p>Native, and emphatically not {@code save()}. Spring Data's {@code save()} on an
     * entity with an <em>assigned</em> identifier calls {@code merge()}, which issues a
     * SELECT and then an UPDATE when the row exists -- so a duplicate request would
     * quietly overwrite the first claim, request hash and all, and no constraint would
     * ever be violated. That is the opposite of what this table is for.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the primary key the arbiter in a single
     * statement: exactly one of two concurrent callers gets 1 back, and there is no
     * read-then-write window between them.
     *
     * @return 1 if this caller claimed the key, 0 if it was already taken
     */
    @Modifying
    @Query(value = "insert into idempotency_keys "
            + "(user_id, idem_key, request_hash, status, created_at) "
            + "values (:userId, :key, :hash, 'IN_PROGRESS', :now) "
            + "on conflict (user_id, idem_key) do nothing",
            nativeQuery = true)
    int insertClaim(@Param("userId") UUID userId, @Param("key") String key,
            @Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from IdempotencyRecord r "
            + "where r.status = com.splitledger.common.idempotency.IdempotencyStatus.IN_PROGRESS "
            + "and r.createdAt < :cutoff")
    int deleteStaleClaims(@Param("cutoff") Instant cutoff);
}
