package com.splitledger.common.idempotency;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository
        extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {

    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from IdempotencyRecord r "
            + "where r.status = com.splitledger.common.idempotency.IdempotencyStatus.IN_PROGRESS "
            + "and r.createdAt < :cutoff")
    int deleteStaleClaims(@Param("cutoff") Instant cutoff);
}
