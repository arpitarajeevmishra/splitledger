package com.splitledger.ledger.infra;

import com.splitledger.ledger.domain.LedgerPosting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, Long> {

    List<LedgerPosting> findAllByTxnId(UUID txnId);

    List<LedgerPosting> findAllByGroupId(UUID groupId);
}
