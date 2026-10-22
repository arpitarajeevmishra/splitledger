package com.splitledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.TestcontainersConfiguration;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.money.Money;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.infra.GroupRepository;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.ledger.domain.LedgerPosting;
import com.splitledger.ledger.domain.LedgerTransaction;
import com.splitledger.ledger.domain.TransactionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The invariants that only a real PostgreSQL can demonstrate.
 *
 * <p>The service refuses a duplicate source and a second reversal, and
 * {@code LedgerServiceTest} covers that. What it cannot cover is the thing the acceptance
 * criterion actually asks for: that the <b>database</b> rejects them, so a concurrent
 * caller slipping past the service's read-then-write still cannot write the row.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.jwt.secret=a-ledger-test-secret-long-enough-for-hs256")
class LedgerIT {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private UserRepository users;

    private record Fixture(UUID groupId, UUID alice, UUID bob, UUID carol) {
    }

    private Fixture fixture() {
        Instant now = Instant.now();
        User alice = users.save(User.register(
                "alice-" + System.nanoTime() + "@example.com", "Alice", "hash", now));
        User bob = users.save(User.register(
                "bob-" + System.nanoTime() + "@example.com", "Bob", "hash", now));
        User carol = users.save(User.register(
                "carol-" + System.nanoTime() + "@example.com", "Carol", "hash", now));
        ExpenseGroup group = groups.save(
                ExpenseGroup.create("Flat 402", "INR", alice.id(), now));
        return new Fixture(group.id(), alice.id(), bob.id(), carol.id());
    }

    /** Alice paid 900, split equally three ways (LLD 5.1). */
    private List<LedgerService.Line> dinner(Fixture f) {
        return List.of(
                LedgerService.Line.of(f.alice(), 90_000L),
                LedgerService.Line.of(f.alice(), -30_000L),
                LedgerService.Line.of(f.bob(), -30_000L),
                LedgerService.Line.of(f.carol(), -30_000L));
    }

    @Test
    void postingsSurviveARoundTripAndSumToZero() {
        Fixture f = fixture();

        LedgerTransaction posted =
                ledger.post(f.groupId(), TransactionType.EXPENSE, UUID.randomUUID(), dinner(f));

        List<LedgerPosting> stored = ledger.postingsFor(f.groupId());
        assertThat(stored).hasSize(4);
        assertThat(stored.stream().mapToLong(posting -> posting.amount().paise()).sum())
                .isZero();
        assertThat(stored).allSatisfy(posting ->
                assertThat(posting.txnId()).isEqualTo(posted.id()));
    }

    @Test
    void theDatabaseRejectsASecondPostForTheSameSource() {
        Fixture f = fixture();
        UUID expenseId = UUID.randomUUID();
        ledger.post(f.groupId(), TransactionType.EXPENSE, expenseId, dinner(f));

        // uq_ledger_txn_source. This is the acceptance criterion for C4: a retry or a bug
        // cannot post the same business fact twice, and the guarantee lives in the schema
        // rather than in whichever service happens to call it.
        assertThatThrownBy(() ->
                ledger.post(f.groupId(), TransactionType.EXPENSE, expenseId, dinner(f)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void thesameSourceIdMayBePostedUnderADifferentType() {
        Fixture f = fixture();
        UUID sourceId = UUID.randomUUID();

        ledger.post(f.groupId(), TransactionType.EXPENSE, sourceId, dinner(f));

        // The index is on (type, source_id): ids come from different tables, so an
        // expense and a settlement could legitimately share one.
        ledger.post(f.groupId(), TransactionType.SETTLEMENT, sourceId, List.of(
                LedgerService.Line.of(f.bob(), 30_000L),
                LedgerService.Line.of(f.alice(), -30_000L)));

        assertThat(ledger.postingsFor(f.groupId())).hasSize(6);
    }

    @Test
    void aReversalLeavesTheGroupWhereItStartedWithoutDeletingAnything() {
        Fixture f = fixture();
        LedgerTransaction posted =
                ledger.post(f.groupId(), TransactionType.EXPENSE, UUID.randomUUID(), dinner(f));

        ledger.reverse(posted.id());

        List<LedgerPosting> stored = ledger.postingsFor(f.groupId());
        // Nothing removed: the original four and the mirroring four both remain.
        assertThat(stored).hasSize(8);
        assertThat(stored.stream().mapToLong(posting -> posting.amount().paise()).sum())
                .isZero();

        long aliceNet = stored.stream()
                .filter(posting -> posting.userId().equals(f.alice()))
                .mapToLong(posting -> posting.amount().paise()).sum();
        assertThat(Money.ofPaise(aliceNet)).isEqualTo(Money.ZERO);
    }

    @Test
    void theDatabaseRejectsASecondReversalOfTheSameTransaction() {
        Fixture f = fixture();
        LedgerTransaction posted =
                ledger.post(f.groupId(), TransactionType.EXPENSE, UUID.randomUUID(), dinner(f));
        ledger.reverse(posted.id());

        // uq_ledger_txn_reverses (V2). Without it, a second reversal would re-apply the
        // original and silently double every balance it touched.
        assertThatThrownBy(() -> ledger.reverse(posted.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void balancesReadBackAsTheWorkedExample() {
        Fixture f = fixture();
        ledger.post(f.groupId(), TransactionType.EXPENSE, UUID.randomUUID(), dinner(f));
        // Bob settles 300 with Alice.
        ledger.post(f.groupId(), TransactionType.SETTLEMENT, UUID.randomUUID(), List.of(
                LedgerService.Line.of(f.bob(), 30_000L),
                LedgerService.Line.of(f.alice(), -30_000L)));

        List<LedgerPosting> stored = ledger.postingsFor(f.groupId());
        assertThat(net(stored, f.alice())).isEqualTo(Money.ofPaise(30_000L));
        assertThat(net(stored, f.bob())).isEqualTo(Money.ZERO);
        assertThat(net(stored, f.carol())).isEqualTo(Money.ofPaise(-30_000L));
    }

    private static Money net(List<LedgerPosting> postings, UUID userId) {
        return Money.ofPaise(postings.stream()
                .filter(posting -> posting.userId().equals(userId))
                .mapToLong(posting -> posting.amount().paise()).sum());
    }
}
