package com.splitledger.ledger.infra;

import com.splitledger.ledger.domain.LedgerTransaction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    List<LedgerTransaction> findAllByGroupId(UUID groupId);

    /** Used to refuse a second reversal of the same transaction. */
    boolean existsByReversesTxnId(UUID reversesTxnId);
}
