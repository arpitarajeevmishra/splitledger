package com.splitledger.activity.infra;

import com.splitledger.expense.domain.Expense;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The activity feed, derived rather than stored (LLD 6.4, finding B3).
 *
 * <p>There is no {@code activity} table on purpose. A log written alongside the facts can
 * drift from them -- a missed insert, a rolled-back transaction, a backfill that skipped a
 * case -- and then the feed quietly says something the balances disagree with. Deriving it
 * means the feed cannot be wrong about anything the tables are right about.
 *
 * <p>The cost is a UNION ALL on every read instead of one indexed scan. At the size this
 * product is built for that is nothing; if it ever stops being nothing, the fix is a
 * materialised view, which keeps the derivation as the definition.
 */
public interface ActivityQueries extends JpaRepository<Expense, UUID> {

    @Query(value = "select * from ("
            + "  select 'EXPENSE_ADDED' as kind, e.id as subject_id, e.created_by as actor_id,"
            + "         e.created_at as at, e.description as description,"
            + "         e.amount_paise as amount_paise, null::uuid as counterparty_id"
            + "  from expenses e where e.group_id = :groupId"
            + "  union all"
            // voided_at, not created_at: a void belongs in the timeline where it happened,
            // not where the expense began. Same for voided_by.
            + "  select 'EXPENSE_VOIDED', e.id, e.voided_by,"
            + "         coalesce(e.voided_at, e.created_at), e.description,"
            + "         e.amount_paise, null::uuid"
            + "  from expenses e"
            + "  where e.group_id = :groupId and e.status = 'VOIDED'"
            + "  union all"
            + "  select 'SETTLEMENT_CONFIRMED', s.id, s.from_user, s.confirmed_at, null,"
            + "         s.amount_paise, s.to_user"
            + "  from settlements s"
            + "  where s.group_id = :groupId and s.status = 'CONFIRMED'"
            + "  union all"
            + "  select 'MEMBER_JOINED', gm.user_id, gm.user_id, gm.joined_at, null,"
            + "         null::bigint, null::uuid"
            + "  from group_members gm where gm.group_id = :groupId"
            + "  union all"
            + "  select 'MEMBER_LEFT', gm.user_id, gm.user_id, gm.left_at, null,"
            + "         null::bigint, null::uuid"
            + "  from group_members gm"
            + "  where gm.group_id = :groupId and gm.left_at is not null"
            + ") feed "
            // subject_id breaks ties so paging cannot show the same row twice or skip one
            // when several things happened in the same transaction.
            + "order by at desc, subject_id desc "
            + "limit :size offset :offset",
            nativeQuery = true)
    List<Object[]> feed(@Param("groupId") UUID groupId, @Param("size") int size,
            @Param("offset") int offset);
}
