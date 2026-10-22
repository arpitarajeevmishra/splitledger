package com.splitledger.balance.application;

import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.balance.domain.PairwiseDebt;
import java.util.List;

/**
 * Both derivations of a group's position, returned together.
 *
 * @param net who is up and who is down, from the ledger
 * @param pairwise who owes whom specifically, from the expenses and settlements that
 *     caused it -- which is the question people actually ask, and the one the ledger
 *     cannot answer on its own
 */
public record GroupBalances(List<MemberBalance> net, List<PairwiseDebt> pairwise) {
}
