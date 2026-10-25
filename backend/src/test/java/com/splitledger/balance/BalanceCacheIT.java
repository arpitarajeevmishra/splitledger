package com.splitledger.balance;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.group.infra.GroupRepository;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

/**
 * The balance cache, against a real database (SL-703).
 *
 * <p>Here rather than in a unit test because the claim is about a transaction boundary.
 * The eviction fires on {@code AFTER_COMMIT}, and nothing with a mocked repository has a
 * commit to be after.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-balance-cache-test-secret-long-enough-for-hs256"
})
class BalanceCacheIT {

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private BalanceService balances;

    @Autowired
    private CacheManager caches;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private GroupMemberRepository groupMembers;

    @Autowired
    private UserRepository users;

    private record Fixture(UUID groupId, UUID alice, UUID bob) {
    }

    private Fixture fixture() {
        Instant now = Instant.now();
        User alice = users.save(User.register(
                "alice-" + System.nanoTime() + "@example.com", "Alice", "hash", now));
        User bob = users.save(User.register(
                "bob-" + System.nanoTime() + "@example.com", "Bob", "hash", now));
        ExpenseGroup group = groups.save(ExpenseGroup.create("Flat 402", "INR", alice.id(), now));
        groupMembers.save(GroupMember.join(group.id(), alice.id(), Role.ADMIN, now));
        groupMembers.save(GroupMember.join(group.id(), bob.id(), Role.MEMBER, now));
        return new Fixture(group.id(), alice.id(), bob.id());
    }

    private void expense(Fixture f, String what, long paise) {
        expenses.record(f.groupId(), f.alice(), what, paise, f.alice(),
                LocalDate.of(2026, 10, 20), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(f.alice(), null),
                        new ExpenseService.Participant(f.bob(), null)));
    }

    private long owedByBob(Fixture f) {
        return balances.of(f.groupId(), f.alice()).net().stream()
                .filter(balance -> balance.userId().equals(f.bob()))
                .mapToLong(balance -> balance.net().paise())
                .sum();
    }

    @Test
    void reading_balances_caches_them() {
        Fixture f = fixture();
        expense(f, "Dinner", 90_000L);

        balances.of(f.groupId(), f.alice());

        // Asserted because everything else here is about the cache being correct, and all
        // of it would pass trivially against a cache that was never populated at all.
        assertThat(caches.getCache("bal")).isNotNull();
        assertThat(caches.getCache("bal").get(f.groupId())).isNotNull();
    }

    @Test
    void a_committed_expense_is_visible_in_the_very_next_read() {
        Fixture f = fixture();
        expense(f, "Dinner", 90_000L);
        assertThat(owedByBob(f)).isEqualTo(-45_000L);

        expense(f, "Taxi", 20_000L);

        // The acceptance criterion. Without eviction this returns the first figure for the
        // next five minutes, and the group simply disbelieves the app.
        assertThat(owedByBob(f)).isEqualTo(-55_000L);
    }

    @Test
    void voiding_an_expense_is_visible_immediately_too() {
        Fixture f = fixture();
        expense(f, "Dinner", 90_000L);
        var recorded = expenses.list(f.groupId(), f.alice()).getFirst();
        assertThat(owedByBob(f)).isEqualTo(-45_000L);

        expenses.voidExpense(recorded.id(), f.alice(), recorded.version());

        // A reversal reaches the ledger through reverse(), not post(). An eviction wired
        // only to post() would pass the test above and leave every correction stale.
        assertThat(owedByBob(f)).isZero();
    }

    @Test
    void one_group_s_write_does_not_evict_another_s_balances() {
        Fixture one = fixture();
        Fixture two = fixture();
        expense(one, "Dinner", 90_000L);
        expense(two, "Dinner", 60_000L);
        balances.of(one.groupId(), one.alice());
        balances.of(two.groupId(), two.alice());

        expense(one, "Taxi", 20_000L);

        // Keyed per group, not cleared wholesale: allEntries=true would be the easy
        // eviction and would throw away every other group's work on every write.
        assertThat(caches.getCache("bal").get(two.groupId())).isNotNull();
    }
}
