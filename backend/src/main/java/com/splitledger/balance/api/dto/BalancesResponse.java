package com.splitledger.balance.api.dto;

import com.splitledger.balance.application.GroupBalances;
import java.util.List;
import java.util.UUID;

/**
 * @param net positive means the group owes them, negative means they owe it
 * @param pairwise who should pay whom, before any simplification
 */
public record BalancesResponse(List<NetBalance> net, List<Debt> pairwise) {

    public record NetBalance(UUID userId, long netPaise, boolean settled) {
    }

    public record Debt(UUID from, UUID to, long amountPaise) {
    }

    public static BalancesResponse of(GroupBalances balances) {
        return new BalancesResponse(
                balances.net().stream()
                        .map(balance -> new NetBalance(balance.userId(),
                                balance.net().paise(), balance.isSettled()))
                        .toList(),
                balances.pairwise().stream()
                        .map(debt -> new Debt(debt.from(), debt.to(), debt.amount().paise()))
                        .toList());
    }
}
