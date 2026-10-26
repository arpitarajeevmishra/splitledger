package com.splitledger.ledger.infra;

import com.splitledger.ledger.domain.LedgerPosting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, Long> {

    List<LedgerPosting> findAllByTxnId(UUID txnId);

    List<LedgerPosting> findAllByGroupId(UUID groupId);

    /**
     * Every posting in the system, summed. Must be exactly zero, forever.
     *
     * <p>Double-entry's one arithmetic promise (ADR-003): every transaction's postings sum
     * to zero, so every transaction ever written sums to zero, so this is zero. A non-zero
     * answer does not mean a balance looks odd -- it means the ledger has lost the property
     * the entire product is built on, and no individual balance can be trusted again until
     * someone finds out why.
     *
     * <p>Which is why it is a metric rather than a test. A test proves it held for the rows
     * a test wrote; this watches the rows real people made.
     */
    @Query(value = "select coalesce(sum(amount_paise), 0) from ledger_postings",
            nativeQuery = true)
    long reconciliationDrift();

    /**
     * Transactions whose own postings do not sum to zero.
     *
     * <p>Strictly stronger than the global sum behind {@code ledger_reconciliation_drift}.
     * That gauge adds up every posting in the system, so two equal and opposite errors --
     * one transaction short by 500 paise, another over by 500 -- cancel out and it reads a
     * contented zero while two groups' balances are both wrong. This asks the question per
     * transaction, where the invariant actually lives (ADR-003).
     *
     * <p>Returns the offending transaction ids with their sums, because "the ledger is
     * broken" is not something anybody can act on and "txn 7f3a… is 500 paise short" is.
     *
     * @return rows of (txn_id, sum), empty when the ledger is sound
     */
    @Query(value = "select txn_id, sum(amount_paise) from ledger_postings "
            + "group by txn_id having sum(amount_paise) <> 0",
            nativeQuery = true)
    List<Object[]> unbalancedTransactions();
}
