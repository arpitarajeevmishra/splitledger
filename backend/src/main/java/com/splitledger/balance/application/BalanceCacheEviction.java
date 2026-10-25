package com.splitledger.balance.application;

import com.splitledger.ledger.application.LedgerChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops a group's cached balances once the write that moved them has actually landed.
 *
 * <p><b>{@code AFTER_COMMIT}, not immediately.</b> Evicting at write time opens a window
 * between the eviction and the commit in which a reader repopulates the cache from data
 * the writer is about to replace -- and that entry then survives, with nothing left to
 * evict it, until the five-minute TTL. The stale value would outlive the write by minutes.
 *
 * <p>Nothing is evicted when a transaction rolls back, which falls out of the same choice:
 * a write that never happened moved no balance, and evicting for it would be a cache miss
 * bought for nothing.
 *
 * <p>A narrow race does remain, and it is worth naming rather than implying away: a reader
 * that begins before the commit and writes its result into the cache after this eviction
 * runs will cache a value that is one write out of date, until the TTL expires it. Closing
 * it properly needs a per-group generation stamp compared on write-back, which the cache
 * abstraction does not offer -- recorded as O-19 rather than left for someone to discover
 * from a balance that will not change.
 */
@Component
public class BalanceCacheEviction {

    private final GroupBalanceCache cache;

    BalanceCacheEviction(GroupBalanceCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLedgerChanged(LedgerChanged change) {
        cache.evict(change.groupId());
    }
}
