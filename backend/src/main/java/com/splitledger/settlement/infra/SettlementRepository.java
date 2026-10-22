package com.splitledger.settlement.infra;

import com.splitledger.settlement.domain.Settlement;
import com.splitledger.settlement.domain.SettlementStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    List<Settlement> findAllByGroupIdOrderByCreatedAtDesc(UUID groupId);

    /** What the sweeper reclaims: anything unfinished whose deadline has passed. */
    List<Settlement> findAllByStatusInAndExpiresAtBefore(Collection<SettlementStatus> statuses,
            Instant cutoff);
}
