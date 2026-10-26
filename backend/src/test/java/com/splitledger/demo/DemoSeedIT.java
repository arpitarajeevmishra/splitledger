package com.splitledger.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.identity.infra.UserRepository;
import com.splitledger.ledger.application.LedgerReconciliation;
import com.splitledger.settlement.application.SettlementService;
import com.splitledger.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The demo data, built the way the application would build it (SL-705).
 *
 * <p>An integration test rather than a unit test because the only interesting claim is that
 * the seed produces data a real flow could have produced. Mocked services would accept
 * splits that do not sum and postings that do not balance -- exactly the data a load test
 * and a public demo must not be measuring or showing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-demo-seed-test-secret-long-enough-for-hs256",
        // The runner is off: this drives the seed directly so the assertions are about the
        // seed rather than about start-up ordering.
        "app.demo.seed-password="
})
class DemoSeedIT {

    @Autowired
    private DemoSeed seed;

    @Autowired
    private UserRepository users;

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private SettlementService settlements;

    @Autowired
    private BalanceService balances;

    @Autowired
    private LedgerReconciliation reconciliation;

    @Test
    void builds_a_group_with_a_history() {
        DemoSeed.Seeded seeded = seed.seed("demo-password");

        assertThat(seeded.created()).isTrue();
        assertThat(expenses.list(seeded.groupId(), seeded.owner())).hasSize(4);
        // One of each, because the thing worth demonstrating is that the allocation does
        // real work -- a demo built entirely from equal splits shows none of it.
        assertThat(expenses.list(seeded.groupId(), seeded.owner()))
                .extracting(expense -> expense.splitType())
                .containsExactlyInAnyOrder(SplitType.EQUAL, SplitType.EXACT,
                        SplitType.PERCENT, SplitType.SHARES);
        assertThat(settlements.list(seeded.groupId(), seeded.owner()))
                .singleElement()
                .extracting(settlement -> settlement.status())
                .isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    void the_seeded_ledger_balances() {
        DemoSeed.Seeded seeded = seed.seed("demo-password");

        // The acceptance criterion, and the reason this goes through the services rather
        // than inserting rows: seeding by INSERT is faster and produces a ledger that does
        // not balance, which would make every performance number measured against it a
        // number about broken data.
        assertThat(reconciliation.reconcile()).isZero();
        assertThat(balances.of(seeded.groupId(), seeded.owner()).net())
                .extracting(balance -> balance.net().paise())
                .satisfies(amounts -> assertThat(amounts.stream().mapToLong(Long::longValue).sum())
                        .isZero());
    }

    @Test
    void running_it_twice_changes_nothing_and_still_says_where_the_group_is() {
        DemoSeed.Seeded first = seed.seed("demo-password");
        long usersAfterFirst = users.count();

        DemoSeed.Seeded again = seed.seed("demo-password");

        // Idempotence matters more than it sounds: this is the one piece of code whose
        // purpose is to write plausible data into a database, so re-running it must be
        // obviously harmless. And it still has to report the group, or "seed, then use it"
        // would work only on a process's first run.
        assertThat(again.created()).isFalse();
        assertThat(again.groupId()).isEqualTo(first.groupId());
        assertThat(users.count()).isEqualTo(usersAfterFirst);
        assertThat(expenses.list(first.groupId(), first.owner())).hasSize(4);
    }

    @Test
    void the_demo_accounts_cannot_collide_with_a_real_signup() {
        seed.seed("demo-password");

        // .invalid is reserved by RFC 2606 and can never be registered, so nobody can own
        // these addresses and nothing addressed to them can leave the building.
        assertThat(DemoSeed.demoEmails()).allSatisfy(email ->
                assertThat(email).endsWith("@splitledger.invalid"));
        assertThat(users.findByEmail(DemoSeed.OWNER_EMAIL)).isPresent();
    }

    @Test
    void the_password_is_stored_hashed() {
        seed.seed("demo-password");

        // A seed that wrote the raw password would be a seed that put a working credential
        // in the database in plain text, on a deployment whose whole point is being public.
        assertThat(users.findByEmail(DemoSeed.OWNER_EMAIL).orElseThrow().passwordHash())
                .isNotEqualTo("demo-password")
                .startsWith("$");
    }
}
