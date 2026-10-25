package com.splitledger.balance.domain;

import java.io.Serializable;
import com.splitledger.common.money.Money;
import java.util.UUID;

/**
 * What the group owes one member, or what they owe it.
 *
 * <p>Positive means the group owes them; negative means they owe the group -- the same
 * convention as a ledger posting, because this is simply their postings added up.
 */
public record MemberBalance(UUID userId, Money net) implements Serializable {

    public static MemberBalance of(UUID userId, long netPaise) {
        return new MemberBalance(userId, Money.ofPaise(netPaise));
    }

    public boolean isSettled() {
        return net.isZero();
    }
}
