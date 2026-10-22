package com.splitledger.expense.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.money.SplitLimits;
import com.splitledger.expense.domain.Expense;
import com.splitledger.expense.domain.ExpenseSplit;
import com.splitledger.expense.domain.SplitCalculator;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.expense.infra.ExpenseRepository;
import com.splitledger.expense.infra.ExpenseSplitRepository;
import com.splitledger.group.application.GroupAccessGuard;
import com.splitledger.group.application.GroupLock;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.ledger.domain.TransactionType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording an expense: the point where everything in Sprint 3 meets.
 *
 * <p>One transaction writes the expense, its splits and the ledger postings. Either all
 * three land or none do -- a split row without a posting is a balance that disagrees with
 * the expense that caused it, and nothing downstream could tell which was right.
 *
 * <p>The transaction opens by locking the group row (ADR-004), before reading membership
 * or computing anything. That ordering is the whole point: two people adding expenses to
 * the same group at the same moment serialise here, while different groups never wait on
 * each other.
 */
@Service
public class ExpenseService {

    private final ExpenseRepository expenses;
    private final ExpenseSplitRepository splits;
    private final GroupMemberRepository members;
    private final GroupAccessGuard guard;
    private final GroupLock groupLock;
    private final LedgerService ledger;
    private final Clock clock;

    @Autowired
    ExpenseService(ExpenseRepository expenses, ExpenseSplitRepository splits,
            GroupMemberRepository members, GroupAccessGuard guard, GroupLock groupLock,
            LedgerService ledger) {
        this(expenses, splits, members, guard, groupLock, ledger, Clock.systemUTC());
    }

    /** Visible for testing. */
    ExpenseService(ExpenseRepository expenses, ExpenseSplitRepository splits,
            GroupMemberRepository members, GroupAccessGuard guard, GroupLock groupLock,
            LedgerService ledger, Clock clock) {
        this.expenses = expenses;
        this.splits = splits;
        this.members = members;
        this.guard = guard;
        this.groupLock = groupLock;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * One participant as the request describes them.
     *
     * @param value meaning depends on the split type: ignored for EQUAL, paise for EXACT,
     *     basis points for PERCENT, a share count for SHARES
     */
    public record Participant(UUID userId, Long value) {
    }

    public record Recorded(Expense expense, List<ExpenseSplit> splits) {
    }

    @Transactional
    public Recorded record(UUID groupId, UUID actorId, String description, long amountPaise,
            UUID paidBy, LocalDate expenseDate, SplitType splitType,
            List<Participant> participants) {
        guard.requireMember(groupId, actorId);
        // Lock first, then read membership: taking the lock after validating would leave a
        // window in which someone leaves the group between the check and the write.
        ExpenseGroup group = groupLock.acquire(groupId);

        // No currency check here on purpose. ExpenseGroup.create refuses anything but INR
        // and the expenses table has CHECK (currency = 'INR'), so a non-INR group cannot
        // exist to be read. A third check would be untestable by construction -- the test
        // for it could not build the input -- and untestable defensive code is a liability,
        // not defence in depth. When multi-currency arrives it belongs in the group module,
        // which owns the column.
        Map<UUID, Long> inputs = inputsOf(participants);
        requireActiveMembers(groupId, paidBy, inputs.keySet());

        SequencedMap<UUID, Long> owed = allocate(splitType, amountPaise, inputs);

        Instant now = Instant.now(clock);
        Expense expense = expenses.save(Expense.record(groupId, paidBy, description, amountPaise,
                group.currency(), splitType, expenseDate, actorId, now));

        return new Recorded(expense, writeSplitsAndPostings(expense, owed, inputs));
    }

    /**
     * Void an expense: reverse what it posted and mark it VOIDED.
     *
     * <p>Nothing is deleted and nothing is edited. The expense stays, the reversal stands
     * beside the original postings, and the group's balances return to exactly where they
     * were before -- which is the acceptance criterion, and the reason corrections work
     * this way rather than by mutating rows.
     *
     * @param expectedVersion from the client's If-Match header; a stale value means
     *     someone else changed this expense since they read it
     */
    @Transactional
    public Expense voidExpense(UUID expenseId, UUID actorId, long expectedVersion) {
        Expense expense = expenses.findById(expenseId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such expense"));
        guard.requireMember(expense.groupId(), actorId);
        groupLock.acquire(expense.groupId());

        requireCurrent(expense, expectedVersion);
        requireActive(expense);

        expense.voidIt(actorId, Instant.now(clock));
        expenses.save(expense);
        // The ledger refuses a second reversal of the same transaction too, so a race
        // that slipped past requireActive still cannot double-count.
        ledger.reverseSource(TransactionType.EXPENSE, expense.id());
        return expense;
    }

    /**
     * Replace an expense with a corrected one.
     *
     * <p>Void plus record, in one transaction: the original is reversed and marked VOIDED,
     * and a new expense points back at it. An edit in place would leave no way to answer
     * "what did this say before, and who changed it" -- which for a figure other people
     * are being asked to pay is the question that matters.
     */
    @Transactional
    public Recorded replace(UUID expenseId, UUID actorId, long expectedVersion,
            String description, long amountPaise, UUID paidBy, LocalDate expenseDate,
            SplitType splitType, List<Participant> participants) {
        Expense superseded = expenses.findById(expenseId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such expense"));
        UUID groupId = superseded.groupId();
        guard.requireMember(groupId, actorId);
        groupLock.acquire(groupId);

        requireCurrent(superseded, expectedVersion);
        requireActive(superseded);

        Map<UUID, Long> inputs = inputsOf(participants);
        requireActiveMembers(groupId, paidBy, inputs.keySet());
        SequencedMap<UUID, Long> owed = allocate(splitType, amountPaise, inputs);

        Instant now = Instant.now(clock);
        superseded.voidIt(actorId, now);
        expenses.save(superseded);
        ledger.reverseSource(TransactionType.EXPENSE, superseded.id());

        Expense replacement = expenses.save(Expense.replacing(superseded, paidBy, description,
                amountPaise, splitType, expenseDate, actorId, now));
        return new Recorded(replacement, writeSplitsAndPostings(replacement, owed, inputs));
    }

    @Transactional(readOnly = true)
    public List<Expense> list(UUID groupId, UUID actorId) {
        guard.requireMember(groupId, actorId);
        return expenses.findAllByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId);
    }

    @Transactional(readOnly = true)
    public Recorded detail(UUID expenseId, UUID actorId) {
        Expense expense = expenses.findById(expenseId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such expense"));
        // Via the group, so a non-member gets the same 404 as for a group that does not
        // exist rather than confirming the expense id is real.
        guard.requireMember(expense.groupId(), actorId);
        return new Recorded(expense, splits.findAllByIdExpenseId(expenseId));
    }

    /**
     * The splits and the ledger transaction for one expense, written together.
     *
     * <p>Shared by recording and replacing so a correction cannot drift from a creation:
     * if the posting convention ever changes, it changes in one place.
     */
    private List<ExpenseSplit> writeSplitsAndPostings(Expense expense,
            SequencedMap<UUID, Long> owed, Map<UUID, Long> inputs) {
        List<ExpenseSplit> saved = new ArrayList<>();
        List<LedgerService.Line> lines = new ArrayList<>();
        // The payer laid out the whole amount, so the group owes them that much.
        lines.add(LedgerService.Line.of(expense.paidBy(), expense.amountPaise()));
        owed.forEach((userId, share) -> {
            saved.add(splits.save(
                    ExpenseSplit.of(expense.id(), userId, share, inputs.get(userId))));
            if (share > 0L) {
                // Their own share comes straight back off, which is why a payer who also
                // took part nets out to what everyone else owes them. A zero share is
                // skipped: the ledger refuses postings that move nothing.
                lines.add(LedgerService.Line.of(userId, -share));
            }
        });
        ledger.post(expense.groupId(), TransactionType.EXPENSE, expense.id(), lines);
        return saved;
    }

    private static void requireCurrent(Expense expense, long expectedVersion) {
        if (expense.version() != expectedVersion) {
            // Someone changed it between their read and their write. Returning 409 rather
            // than applying it blindly is the point of optimistic locking: the client has
            // to look at what changed before deciding again.
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This expense changed since you read it; reload and try again");
        }
    }

    private static void requireActive(Expense expense) {
        if (!expense.isActive()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "That expense has already been voided");
        }
    }

    private static Map<UUID, Long> inputsOf(List<Participant> participants) {
        SequencedMap<UUID, Long> inputs = new LinkedHashMap<>();
        for (Participant participant : participants) {
            // containsKey, not put() != null: an EQUAL split carries no value, so every
            // put returns null and a duplicate would pass unnoticed -- silently splitting
            // the expense among fewer people than the request listed.
            if (inputs.containsKey(participant.userId())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "The same participant is listed twice");
            }
            inputs.put(participant.userId(), participant.value());
        }
        return inputs;
    }

    private void requireActiveMembers(UUID groupId, UUID paidBy, Set<UUID> participants) {
        Set<UUID> active = new HashSet<>();
        for (GroupMember member : members.findAllByIdGroupIdAndLeftAtIsNull(groupId)) {
            active.add(member.userId());
        }
        if (!active.contains(paidBy)) {
            throw new ApiException(ErrorCode.PARTICIPANT_NOT_MEMBER,
                    "The payer is not an active member of this group");
        }
        for (UUID participant : participants) {
            if (!active.contains(participant)) {
                // Someone from another group, or someone who has left: either way their
                // share would be a debt nobody in this group can settle.
                throw new ApiException(ErrorCode.PARTICIPANT_NOT_MEMBER,
                        "A participant is not an active member of this group");
            }
        }
    }

    private static SequencedMap<UUID, Long> allocate(SplitType splitType, long amountPaise,
            Map<UUID, Long> inputs) {
        return switch (splitType) {
            case EQUAL -> SplitCalculator.equalSplit(amountPaise, inputs.keySet());
            case EXACT -> SplitCalculator.exactSplit(amountPaise, requireValues(inputs));
            case PERCENT -> SplitCalculator.percentSplit(amountPaise, requireValues(inputs));
            case SHARES -> SplitCalculator.sharesSplit(amountPaise, requireValues(inputs));
        };
    }

    private static Map<UUID, Long> requireValues(Map<UUID, Long> inputs) {
        for (Map.Entry<UUID, Long> entry : inputs.entrySet()) {
            if (entry.getValue() == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Every participant needs a value for this split type");
            }
        }
        return inputs;
    }

    /** Exposed so the API layer can state the limits it enforces. */
    public static int maxParticipants() {
        return SplitLimits.MAX_PARTICIPANTS;
    }
}
