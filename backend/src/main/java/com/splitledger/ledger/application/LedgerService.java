package com.splitledger.ledger.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.money.Money;
import com.splitledger.ledger.domain.LedgerPosting;
import com.splitledger.ledger.domain.LedgerTransaction;
import com.splitledger.ledger.domain.TransactionType;
import com.splitledger.ledger.infra.LedgerPostingRepository;
import com.splitledger.ledger.infra.LedgerTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger: balanced, append-only, and deliberately ignorant of why.
 *
 * <p>It knows how to record that money moved between people in a group and nothing else.
 * It has never heard of an expense or a settlement -- those modules hand it postings that
 * already sum to zero, and ArchUnit enforces that the dependency only runs one way
 * (ADR-001, ADR-003). The payoff is that every balance in the product has exactly one
 * source, and new kinds of money movement cannot introduce a second way of computing one.
 *
 * <p>Two invariants, each checked here <em>and</em> in the database, because a service
 * check is a read followed by a write and concurrent callers slip between the two:
 *
 * <ol>
 *   <li>Every transaction's postings sum to zero. Money is never created or destroyed,
 *       only moved, so a non-zero sum is a bug in the caller's arithmetic.</li>
 *   <li>One transaction per business fact, and one reversal per transaction.</li>
 * </ol>
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository transactions;
    private final LedgerPostingRepository postings;
    private final Clock clock;

    @Autowired
    LedgerService(LedgerTransactionRepository transactions, LedgerPostingRepository postings) {
        this(transactions, postings, Clock.systemUTC());
    }

    /** Visible for testing. */
    LedgerService(LedgerTransactionRepository transactions, LedgerPostingRepository postings,
            Clock clock) {
        this.transactions = transactions;
        this.postings = postings;
        this.clock = clock;
    }

    /**
     * One side of a transaction, as the calling module describes it.
     *
     * <p>Positive means the group owes this user; negative means they owe the group.
     */
    public record Line(UUID userId, Money amount) {

        public static Line of(UUID userId, long paise) {
            return new Line(userId, Money.ofPaise(paise));
        }
    }

    /**
     * Record a business fact as a balanced transaction.
     *
     * @param sourceId what caused it -- an expense id, a settlement id. The ledger does
     *     not resolve it; it only refuses to record the same one twice.
     */
    @Transactional
    public LedgerTransaction post(UUID groupId, TransactionType type, UUID sourceId,
            List<Line> lines) {
        if (type == TransactionType.REVERSAL) {
            // Reversals are derived from the transaction they undo, never posted
            // directly, or their postings could fail to mirror the original.
            throw new IllegalArgumentException("Use reverse() to post a reversal");
        }
        requireBalanced(lines);

        LedgerTransaction transaction =
                LedgerTransaction.of(groupId, type, sourceId, Instant.now(clock));
        return write(transaction, lines, "That has already been posted to the ledger");
    }

    /**
     * Undo a transaction by posting its exact opposite (LLD 5.1).
     *
     * <p>Nothing is edited or removed: the original stays, the reversal stands beside it,
     * and the two sum to zero. Reading the group's postings afterwards gives the same
     * answer as if the original had never happened, while the history still shows that it
     * did and when it was corrected.
     */
    @Transactional
    public LedgerTransaction reverse(UUID transactionId) {
        LedgerTransaction original = transactions.findById(transactionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No such ledger transaction"));

        if (original.isReversal()) {
            // Reversing a reversal would re-apply the original by a confusing route. If
            // that is what someone wants, they should post the fact again.
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A reversal cannot itself be reversed");
        }
        if (transactions.existsByReversesTxnId(transactionId)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "That transaction has already been reversed");
        }

        List<Line> mirrored = new ArrayList<>();
        for (LedgerPosting posting : postings.findAllByTxnId(transactionId)) {
            mirrored.add(new Line(posting.userId(), posting.amount().negated()));
        }
        if (mirrored.isEmpty()) {
            throw new IllegalStateException(
                    "Ledger transaction " + transactionId + " has no postings");
        }

        LedgerTransaction reversal =
                LedgerTransaction.reversalOf(original, Instant.now(clock));
        return write(reversal, mirrored, "That transaction has already been reversed");
    }

    /** Every posting for a group, for whoever computes balances from them. */
    @Transactional(readOnly = true)
    public List<LedgerPosting> postingsFor(UUID groupId) {
        return postings.findAllByGroupId(groupId);
    }

    private LedgerTransaction write(LedgerTransaction transaction, List<Line> lines,
            String duplicateMessage) {
        try {
            transactions.save(transaction);
            for (Line line : lines) {
                postings.save(LedgerPosting.of(transaction.id(), transaction.groupId(),
                        line.userId(), line.amount()));
            }
            // Flushed here so a unique-index violation surfaces inside this method rather
            // than at commit, where the controller has already returned and the handler
            // has nothing left to translate.
            transactions.flush();
            postings.flush();
            return transaction;
        } catch (DataIntegrityViolationException ex) {
            // The index, not the pre-check, is what actually decides: two concurrent
            // callers both pass the read above and exactly one of them wins here.
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, duplicateMessage);
        }
    }

    private static void requireBalanced(List<Line> lines) {
        if (lines.size() < 2) {
            // One posting cannot sum to zero unless it is zero, and a zero posting is
            // already refused below.
            throw new IllegalArgumentException("A ledger transaction needs at least two postings");
        }
        Money total = Money.ZERO;
        for (Line line : lines) {
            if (line.amount().isZero()) {
                throw new IllegalArgumentException("A posting of zero moves nothing");
            }
            total = total.plus(line.amount());
        }
        if (!total.isZero()) {
            // Not an ApiException: a caller handing the ledger unbalanced postings is a
            // programming error in another module, not something a user did wrong, and it
            // must never reach the database.
            throw new IllegalArgumentException(
                    "Ledger postings must sum to zero, but summed to " + total);
        }
    }
}
