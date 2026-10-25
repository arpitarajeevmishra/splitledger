package com.splitledger.balance.domain;

import java.io.Serializable;
import java.util.List;

/**
 * Everything derived from one group's postings, computed together and cached as one thing.
 *
 * <p><b>One entry, not three.</b> The three views are derived from the same postings, and
 * caching them separately would allow a partial eviction to leave a fresh net balance beside
 * a stale pairwise one -- two numbers on one screen that disagree, which is precisely the
 * bug this product has already shipped once (D-132). A single entry cannot be half-evicted.
 *
 * <p>The cost is that asking only for the net figures computes the other two on a miss. That
 * is two queries and a simplification, once per five minutes per group, in exchange for an
 * eviction that cannot go halfway.
 *
 * <p>{@code Serializable} because ADR-015 keeps a Redis implementation behind the same
 * interface, and Spring Boot's default Redis cache serializer is JDK serialization. A value
 * type that only works in Caffeine would make that claim quietly untrue.
 */
public record BalanceSnapshot(List<MemberBalance> net, List<PairwiseDebt> pairwise,
        List<Transfer> simplified) implements Serializable {
}
