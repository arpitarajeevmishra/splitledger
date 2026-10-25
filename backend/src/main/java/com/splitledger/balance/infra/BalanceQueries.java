package com.splitledger.balance.infra;

import com.splitledger.ledger.domain.LedgerPosting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The two independent derivations of what a group owes (LLD 6.1 and 6.2).
 *
 * <p>They are deliberately not the same query over the same rows. Net comes from the
 * ledger; pairwise comes from the expenses and settlements that produced it. Either alone
 * would be believable; the fact that they agree is what makes the numbers worth trusting,
 * and a test asserts exactly that.
 */
public interface BalanceQueries extends JpaRepository<LedgerPosting, Long> {

    /** Net per user, from the ledger. Users whose postings cancel out are omitted. */
    @Query(value = "select user_id, sum(amount_paise) as net "
            + "from ledger_postings where group_id = :groupId "
            + "group by user_id having sum(amount_paise) <> 0",
            nativeQuery = true)
    List<Object[]> netByUser(@Param("groupId") UUID groupId);

    /**
     * This person's net in each group where it is not zero, across every group at once.
     *
     * <p>From the ledger, like {@link #netByUser}, because the ledger is what a balance
     * <em>is</em> -- summing expenses and settlements again here would be a second
     * derivation that could disagree with the first.
     *
     * <p>No membership filter, and none is needed: a member may only leave a group once
     * their balance is zero, so a group they have left contributes nothing to this sum by
     * construction. Filtering on membership would add a join to exclude rows that are
     * already zero.
     *
     * @return rows of (group_id, net), omitting groups that are settled
     */
    @Query(value = "select group_id, sum(amount_paise) as net "
            + "from ledger_postings where user_id = :userId "
            + "group by group_id having sum(amount_paise) <> 0",
            nativeQuery = true)
    List<Object[]> netByGroupForUser(@Param("userId") UUID userId);

    /**
     * Who owes whom, from the source tables.
     *
     * <p>A participant's share is a debt to whoever paid -- except their own share of
     * their own expense, which is not a debt to themselves. A confirmed settlement from A
     * to B cancels part of what A owes B, so it joins the union with the pair reversed.
     *
     * <p>Normalised onto (least, greatest) so A-owes-B and B-owes-A collapse into one
     * signed row per pair, rather than two rows that would then have to be reconciled.
     */
    @Query(value = "with debts as ("
            + "  select s.user_id as debtor, e.paid_by as creditor, s.owed_paise as amt"
            + "  from expense_splits s"
            + "  join expenses e on e.id = s.expense_id"
            + "  where e.group_id = :groupId and e.status = 'ACTIVE'"
            + "    and s.user_id <> e.paid_by"
            + "  union all"
            + "  select st.to_user, st.from_user, st.amount_paise"
            + "  from settlements st"
            + "  where st.group_id = :groupId and st.status = 'CONFIRMED'"
            + ") "
            + "select least(debtor, creditor) as user_a,"
            + "       greatest(debtor, creditor) as user_b,"
            + "       sum(case when debtor < creditor then amt else -amt end) as a_owes_b "
            + "from debts group by 1, 2 "
            + "having sum(case when debtor < creditor then amt else -amt end) <> 0",
            nativeQuery = true)
    List<Object[]> pairwise(@Param("groupId") UUID groupId);
}
