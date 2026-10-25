package com.splitledger.balance.application;

import com.splitledger.balance.domain.BalanceSnapshot;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.balance.domain.Transfer;
import com.splitledger.balance.infra.BalanceQueries;
import com.splitledger.group.application.GroupAccessGuard;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a group owes, two ways.
 *
 * <p><b>Authorisation lives here; the numbers live in {@link GroupBalanceCache}.</b> That
 * split is not tidiness. A {@code @Cacheable} on these methods would return a hit through
 * the proxy without the body running, and the membership check is in the body -- so the
 * cache would quietly become an authorisation bypass the moment any member warmed an entry.
 * Every method below guards first and reads second, and the thing it reads cannot
 * authorise anything.
 */
@Service
public class BalanceService {

    private final BalanceQueries queries;
    private final GroupBalanceCache cache;
    private final GroupAccessGuard guard;

    BalanceService(BalanceQueries queries, GroupBalanceCache cache, GroupAccessGuard guard) {
        this.queries = queries;
        this.cache = cache;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public GroupBalances of(UUID groupId, UUID actorId) {
        guard.requireMember(groupId, actorId);
        // One lookup, not two. Reading the snapshot twice would let an eviction land
        // between the calls and return a net balance from one and a pairwise view from
        // another -- the disagreement BalanceSnapshot exists to make impossible.
        BalanceSnapshot snapshot = cache.of(groupId);
        return new GroupBalances(snapshot.net(), snapshot.pairwise());
    }

    /**
     * Where this person stands across all their groups.
     *
     * <p>No group id and so no {@code requireMember}: the only argument is the caller's
     * own id, taken from their token rather than from the request, so there is nothing
     * here to authorise against. Passing a user id in would make it an endpoint for
     * reading other people's finances.
     */
    @Transactional(readOnly = true)
    public NetPosition netAcrossGroups(UUID userId) {
        long total = 0L;
        int groups = 0;
        for (Object[] row : queries.netByGroupForUser(userId)) {
            // The query already drops the settled groups, so every row counts.
            total += ((Number) row[1]).longValue();
            groups++;
        }
        return new NetPosition(total, groups);
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
        return cache.of(groupId).net();
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
        return cache.of(groupId).simplified();
    }

}
