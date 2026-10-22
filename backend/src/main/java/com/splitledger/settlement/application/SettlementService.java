package com.splitledger.settlement.application;

import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.money.Money;
import com.splitledger.group.application.GroupAccessGuard;
import com.splitledger.group.application.GroupLock;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.settlement.domain.Settlement;
import com.splitledger.settlement.domain.SettlementMethod;
import com.splitledger.settlement.infra.SettlementRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating a settlement: one person declaring they are paying another.
 *
 * <p>Nothing is posted to the ledger here. Creating a settlement records an <em>intent</em>
 * to pay; the money only reaches the ledger when someone confirms it arrived (SL-503).
 * Posting at creation would let anyone clear a debt by announcing they had paid it.
 */
@Service
public class SettlementService {

    /** Razorpay will not take less than a rupee; we say so ourselves (LLD 7.4, finding C5). */
    public static final long GATEWAY_MINIMUM_PAISE = 100L;

    private final SettlementRepository settlements;
    private final GroupMemberRepository members;
    private final GroupAccessGuard guard;
    private final GroupLock groupLock;
    private final BalanceService balances;
    private final Clock clock;

    @Autowired
    SettlementService(SettlementRepository settlements, GroupMemberRepository members,
            GroupAccessGuard guard, GroupLock groupLock, BalanceService balances) {
        this(settlements, members, guard, groupLock, balances, Clock.systemUTC());
    }

    /** Visible for testing. */
    SettlementService(SettlementRepository settlements, GroupMemberRepository members,
            GroupAccessGuard guard, GroupLock groupLock, BalanceService balances, Clock clock) {
        this.settlements = settlements;
        this.members = members;
        this.guard = guard;
        this.groupLock = groupLock;
        this.balances = balances;
        this.clock = clock;
    }

    /**
     * Record that {@code payer} intends to pay {@code payee}.
     *
     * <p>The payer is always the caller. Letting one person create a settlement on
     * another's behalf would let anyone mark their own debts as being paid by someone
     * else.
     */
    @Transactional
    public Settlement create(UUID groupId, UUID payerId, UUID payeeId, long amountPaise,
            SettlementMethod method) {
        guard.requireMember(groupId, payerId);
        groupLock.acquire(groupId);

        requireActiveMembers(groupId, payerId, payeeId);
        requireGatewayMinimum(method, amountPaise);
        requireWithinOutstanding(groupId, payerId, payeeId, amountPaise);

        return settlements.save(Settlement.initiate(groupId, payerId, payeeId, amountPaise,
                method, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<Settlement> list(UUID groupId, UUID actorId) {
        guard.requireMember(groupId, actorId);
        return settlements.findAllByGroupIdOrderByCreatedAtDesc(groupId);
    }

    @Transactional(readOnly = true)
    public Settlement detail(UUID settlementId, UUID actorId) {
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such settlement"));
        // Via the group, so a stranger gets the same 404 as for a group that is not theirs
        // rather than confirming the settlement id is real.
        guard.requireMember(settlement.groupId(), actorId);
        return settlement;
    }

    /**
     * The ADR-010 rule: the limit is the <b>net</b> balance, never the pairwise debt.
     *
     * <p>This is the whole reason simplification is usable. A simplified transfer is
     * deliberately a payment between two people who often have no direct debt at all --
     * A owes B, B owes C, so the plan says A pays C. Validating against the pairwise
     * figure would reject every one of those, which is to say it would reject exactly the
     * payments the feature exists to suggest.
     *
     * <p>Pending settlements are deliberately <em>not</em> subtracted from the limit. Two
     * people could each start one and together exceed the debt, so confirmation re-runs
     * this same check and the second one fails there (SL-503). The trade-off is accepted
     * because holding a debt against an unconfirmed intent would let anyone block a
     * group's settlements by starting one and walking away.
     */
    private void requireWithinOutstanding(UUID groupId, UUID payerId, UUID payeeId,
            long amountPaise) {
        Map<UUID, Long> net = new HashMap<>();
        for (MemberBalance balance : balances.netOnly(groupId, payerId)) {
            net.put(balance.userId(), balance.net().paise());
        }

        long payerNet = net.getOrDefault(payerId, 0L);
        long payeeNet = net.getOrDefault(payeeId, 0L);

        if (payerNet >= 0L) {
            throw new ApiException(ErrorCode.SETTLEMENT_EXCEEDS_OUTSTANDING,
                    "You do not owe this group anything");
        }
        if (payeeNet <= 0L) {
            throw new ApiException(ErrorCode.SETTLEMENT_EXCEEDS_OUTSTANDING,
                    "They are not owed anything by this group");
        }

        long limit = Math.min(-payerNet, payeeNet);
        if (amountPaise > limit) {
            throw new ApiException(ErrorCode.SETTLEMENT_EXCEEDS_OUTSTANDING,
                    "That is more than the " + Money.ofPaise(limit) + " outstanding between you");
        }
    }

    private void requireGatewayMinimum(SettlementMethod method, long amountPaise) {
        if (method == SettlementMethod.RAZORPAY && amountPaise < GATEWAY_MINIMUM_PAISE) {
            // Refused here with our own error rather than by an opaque gateway failure
            // several steps later (finding C5).
            throw new ApiException(ErrorCode.SETTLEMENT_BELOW_GATEWAY_MINIMUM,
                    "Card payments start at " + Money.ofPaise(GATEWAY_MINIMUM_PAISE));
        }
    }

    private void requireActiveMembers(UUID groupId, UUID payerId, UUID payeeId) {
        Set<UUID> active = new HashSet<>();
        for (GroupMember member : members.findAllByIdGroupIdAndLeftAtIsNull(groupId)) {
            active.add(member.userId());
        }
        if (!active.contains(payerId) || !active.contains(payeeId)) {
            // A debt to someone who has left is settled by re-inviting them, not by paying
            // into a group they are no longer part of.
            throw new ApiException(ErrorCode.PARTICIPANT_NOT_MEMBER,
                    "Both people must be active members of this group");
        }
    }
}
