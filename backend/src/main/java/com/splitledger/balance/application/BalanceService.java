package com.splitledger.balance.application;

import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.balance.domain.PairwiseDebt;
import com.splitledger.balance.domain.Simplification;
import com.splitledger.balance.domain.Transfer;
import com.splitledger.balance.infra.BalanceQueries;
import com.splitledger.group.application.GroupAccessGuard;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.infra.GroupMemberRepository;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a group owes, two ways.
 *
 * <p><b>Not cached.</b> The {@code bal} cache exists and is configured, but wiring it here
 * without invalidation on every write that moves a balance would serve a stale number --
 * and a balance that is confidently wrong is worse than one that took an extra query.
 * Caching lands in S8 together with the invalidation that makes it safe.
 */
@Service
public class BalanceService {

    private final BalanceQueries queries;
    private final GroupMemberRepository members;
    private final GroupAccessGuard guard;

    BalanceService(BalanceQueries queries, GroupMemberRepository members,
            GroupAccessGuard guard) {
        this.queries = queries;
        this.members = members;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public GroupBalances of(UUID groupId, UUID actorId) {
        guard.requireMember(groupId, actorId);
        return new GroupBalances(net(groupId), pairwise(groupId));
    }

    /**
     * Net positions only, for callers that authorise against them.
     *
     * <p>Separate from {@link #of} because settlement validation needs the net figures and
     * nothing else; computing the pairwise view as well would be a second query to answer
     * a question nobody asked.
     */
    @Transactional(readOnly = true)
    public List<MemberBalance> netOnly(UUID groupId, UUID actorId) {
        guard.requireMember(groupId, actorId);
        return net(groupId);
    }

    /**
     * The fewest payments that would settle the group.
     *
     * <p>Computed from the net positions, not from who owes whom: that is what lets a
     * three-way circle of debts collapse into one payment, and why a settlement is
     * validated against net balances rather than pairwise debt (ADR-010).
     */
    @Transactional(readOnly = true)
    public List<Transfer> simplified(UUID groupId, UUID actorId) {
        guard.requireMember(groupId, actorId);

        Map<UUID, Long> net = new HashMap<>();
        for (MemberBalance balance : net(groupId)) {
            net.put(balance.userId(), balance.net().paise());
        }
        return Simplification.of(net);
    }

    /**
     * Net position per member, from the ledger.
     *
     * <p>Every active member appears, including those at zero. The query omits them --
     * their postings cancel out -- but "settled up" is a fact worth showing, and a member
     * silently missing from the list reads as a bug.
     */
    private List<MemberBalance> net(UUID groupId) {
        Map<UUID, Long> byUser = new HashMap<>();
        for (Object[] row : queries.netByUser(groupId)) {
            byUser.put(asUuid(row[0]), asLong(row[1]));
        }

        List<MemberBalance> balances = new ArrayList<>();
        for (GroupMember member : members.findAllByIdGroupIdAndLeftAtIsNull(groupId)) {
            balances.add(MemberBalance.of(member.userId(),
                    byUser.getOrDefault(member.userId(), 0L)));
        }
        // A departed member with an outstanding balance cannot exist -- leaving requires
        // settling first -- but a departed member who settled still has postings, and
        // including them here would list someone no longer in the group.
        return balances;
    }

    private List<PairwiseDebt> pairwise(UUID groupId) {
        List<PairwiseDebt> debts = new ArrayList<>();
        for (Object[] row : queries.pairwise(groupId)) {
            // a_owes_b is signed: negative simply means the pair runs the other way, and
            // PairwiseDebt normalises it so the amount is always positive.
            debts.add(PairwiseDebt.of(asUuid(row[0]), asUuid(row[1]), asLong(row[2])));
        }
        return debts;
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    /**
     * {@code sum()} over a bigint comes back as BigInteger on PostgreSQL, not Long. Left
     * unhandled this is a ClassCastException the first time a group has any postings at
     * all, which no unit test with a mocked repository would ever see.
     */
    private static long asLong(Object value) {
        return switch (value) {
            case BigInteger big -> big.longValueExact();
            case Number number -> number.longValue();
            default -> Long.parseLong(value.toString());
        };
    }
}
