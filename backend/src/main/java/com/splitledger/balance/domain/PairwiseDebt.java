package com.splitledger.balance.domain;

import java.io.Serializable;
import com.splitledger.common.money.Money;
import java.util.UUID;

/**
 * One person owes another a specific amount, derived from the expenses and settlements
 * that created the debt rather than from the ledger.
 *
 * <p>Always normalised so {@code amount} is positive: "A owes B 300" and "B is owed 300 by
 * A" are the same fact, and letting both spellings exist would double every debt in any
 * sum over this list.
 */
public record PairwiseDebt(UUID from, UUID to, Money amount) implements Serializable {

    public static PairwiseDebt of(UUID debtor, UUID creditor, long amountPaise) {
        if (amountPaise < 0) {
            return new PairwiseDebt(creditor, debtor, Money.ofPaise(-amountPaise));
        }
        return new PairwiseDebt(debtor, creditor, Money.ofPaise(amountPaise));
    }
}
