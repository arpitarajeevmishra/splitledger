package com.splitledger.balance.application;

import com.splitledger.balance.domain.BalanceSnapshot;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.balance.domain.PairwiseDebt;
import com.splitledger.balance.domain.Simplification;
import com.splitledger.balance.infra.BalanceQueries;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.infra.GroupMemberRepository;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a group's balances are computed, and where they are cached (SL-703, LLD 13).
 *
 * <p><b>A separate bean from {@link BalanceService}, and that is the point.</b> The service
 * authorises; this computes. Were {@code @Cacheable} on the service's own method, a cache
 * hit would return through the proxy without the method body ever running -- and the
 * membership check lives in that body. The cache would become an authorisation bypass:
 * anyone who could name a group id would be served its balances as soon as a member had
 * warmed the entry. Self-invocation would not even go through the proxy, so the mistake
 * would look like it worked.
 *
 * <p>Keyed on the group alone. Balances are a property of the group, not of who is asking,
 * so keying on the caller as well would multiply the entries by the membership and cache
 * the same numbers once per person.
 */
@Component
public class GroupBalanceCache {

    private final BalanceQueries queries;
    private final GroupMemberRepository members;

    GroupBalanceCache(BalanceQueries queries, GroupMemberRepository members) {
        this.queries = queries;
        this.members = members;
    }

    @Cacheable(cacheNames = "bal", key = "#groupId")
    @Transactional(readOnly = true)
    public BalanceSnapshot of(UUID groupId) {
        List<MemberBalance> net = net(groupId);
        Map<UUID, Long> byUser = new HashMap<>();
        for (MemberBalance balance : net) {
            byUser.put(balance.userId(), balance.net().paise());
        }
        return new BalanceSnapshot(net, pairwise(groupId), Simplification.of(byUser));
    }

    /**
     * Drop a group's entry.
     *
     * <p>An empty method with an annotation, which looks odd and is the idiomatic way to
     * say "evict this key" without also having something to compute.
     */
    @CacheEvict(cacheNames = "bal", key = "#groupId")
    public void evict(UUID groupId) {
        // The annotation is the behaviour.
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
