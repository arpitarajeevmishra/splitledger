package com.splitledger.settlement.infra;

import com.splitledger.settlement.domain.Settlement;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.settlement.domain.SettlementStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    List<Settlement> findAllByGroupIdOrderByCreatedAtDesc(UUID groupId);

    /**
     * Just the group, so the lock can be taken before the settlement is loaded. Loading
     * it first would put it in the persistence context at its pre-lock status, and every
     * later read in the transaction would hand back that stale copy (D-83).
     */
    @Query("select s.groupId from Settlement s where s.id = :settlementId")
    Optional<UUID> findGroupIdById(@Param("settlementId") UUID settlementId);

    /** Sandbox settlements waiting for their stand-in gateway to answer. */
    List<Settlement> findAllByMethodAndStatus(PaymentMethod method, SettlementStatus status);

    /** What the sweeper reclaims: anything unfinished whose deadline has passed. */
    List<Settlement> findAllByStatusInAndExpiresAtBefore(Collection<SettlementStatus> statuses,
            Instant cutoff);
}
