package com.splitledger.ledger.infra;

import com.splitledger.ledger.domain.LedgerTransaction;
import com.splitledger.ledger.domain.TransactionType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    List<LedgerTransaction> findAllByGroupId(UUID groupId);

    /** Used to refuse a second reversal of the same transaction. */
    boolean existsByReversesTxnId(UUID reversesTxnId);

    /**
     * The transaction a business fact produced. Unique for non-reversals by
     * uq_ledger_txn_source, so this can return at most one.
     */
    Optional<LedgerTransaction> findByTypeAndSourceId(TransactionType type, UUID sourceId);
}
