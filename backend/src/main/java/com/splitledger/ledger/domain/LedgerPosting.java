package com.splitledger.ledger.domain;

import com.splitledger.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One side of a transaction.
 *
 * <p>Sign convention (LLD 5.1), and it is worth stating plainly because every balance in
 * the product reads from it: <b>positive means the group owes this user; negative means
 * this user owes the group.</b> So the person who paid for dinner gets a positive posting
 * for what they laid out and a negative one for their own share.
 *
 * <p>Zero is not a valid amount -- a posting that moves nothing is noise in a ledger, and
 * the database rejects it too.
 */
@Entity
@Table(name = "ledger_postings")
public class LedgerPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "txn_id", nullable = false, updatable = false)
    private UUID txnId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    protected LedgerPosting() {
        // for JPA
    }

    private LedgerPosting(UUID txnId, UUID groupId, UUID userId, long amountPaise) {
        this.txnId = txnId;
        this.groupId = groupId;
        this.userId = userId;
        this.amountPaise = amountPaise;
    }

    public static LedgerPosting of(UUID txnId, UUID groupId, UUID userId, Money amount) {
        return new LedgerPosting(txnId, groupId, userId, amount.paise());
    }

    public Long id() {
        return id;
    }

    public UUID txnId() {
        return txnId;
    }

    public UUID groupId() {
        return groupId;
    }

    public UUID userId() {
        return userId;
    }

    public Money amount() {
        return Money.ofPaise(amountPaise);
    }
}
