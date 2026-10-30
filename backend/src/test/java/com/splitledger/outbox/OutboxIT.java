package com.splitledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.TestcontainersConfiguration;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.group.infra.GroupRepository;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import com.splitledger.outbox.application.OutboxPublisher;
import com.splitledger.outbox.application.OutboxRecorder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox against a real database (SL-701, ADR-005).
 *
 * <p>Here rather than in a unit test because every claim worth making is a claim about a
 * transaction: that the event and the business rows share one, that a rollback takes both,
 * and that {@code FOR UPDATE SKIP LOCKED} means what the comment says. A mock repository
 * would agree with any of those regardless of whether they were true.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=an-outbox-test-secret-long-enough-for-hs256",
        // The scheduled publisher is disabled for these: a background poll draining the
        // table mid-assertion would make "the event is still unpublished" a race rather
        // than a fact. Publishing is driven explicitly instead.
        // A cron that never fires this century, so publishing is driven only
        // by the explicit calls below.
        "app.outbox.poll-cron=0 0 5 31 2 ?",
        // And no nudge, for the same reason: publishing has to happen
        // only where these tests ask for it, or "still unpublished" is
        // a race against a thread that commits faster than the assert.
        "app.outbox.nudge-enabled=false"
})
class OutboxIT {

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private GroupMemberRepository groupMembers;

    @Autowired
    private UserRepository users;

    @Autowired
    private OutboxRecorder recorder;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

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

    private long unpublished() {
        return jdbc.queryForObject(
                "select count(*) from outbox_events where published_at is null", Long.class);
    }

    @Test
    void an_expense_and_its_event_commit_together() {
        Fixture f = fixture();
        long before = unpublished();

        expenses.record(f.groupId(), f.alice(), "Dinner", 90_000L, f.alice(),
                LocalDate.of(2026, 10, 20), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(f.alice(), null),
                        new ExpenseService.Participant(f.bob(), null)));

        assertThat(unpublished()).isEqualTo(before + 1);
    }

    @Test
    void a_rollback_takes_the_event_with_it() {
        Fixture f = fixture();
        long before = unpublished();

        // The dual-write bug, attempted: write the business row, record the event, then
        // fail. If the event survived this, the outbox would be announcing an expense that
        // does not exist -- the exact failure publishing-before-commit produces, and the
        // reason the row lives in the same transaction rather than being sent from one.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            expenses.record(f.groupId(), f.alice(), "Dinner", 90_000L, f.alice(),
                    LocalDate.of(2026, 10, 20), SplitType.EQUAL,
                    List.of(new ExpenseService.Participant(f.alice(), null),
                            new ExpenseService.Participant(f.bob(), null)));
            throw new IllegalStateException("something later in the request failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(unpublished()).isEqualTo(before);
    }

    @Test
    void recording_an_event_outside_a_transaction_is_refused() {
        Fixture f = fixture();

        // Propagation.MANDATORY, and worth a test because the failure it prevents is
        // invisible: an event recorded in its own little transaction commits whether or
        // not the business write does, which is the dual write the outbox exists to stop.
        assertThatThrownBy(() -> recorder.record("expense", UUID.randomUUID(), f.groupId(),
                "expense.created", java.util.Map.of("a", 1)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void publishing_stamps_the_events_it_sent_and_leaves_nothing_behind() {
        Fixture f = fixture();
        expenses.record(f.groupId(), f.alice(), "Dinner", 90_000L, f.alice(),
                LocalDate.of(2026, 10, 20), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(f.alice(), null),
                        new ExpenseService.Participant(f.bob(), null)));
        assertThat(unpublished()).isPositive();

        publisher.publishBatch();

        assertThat(unpublished()).isZero();
    }

    @Test
    void a_second_poll_does_not_republish_what_the_first_one_sent() {
        Fixture f = fixture();
        expenses.record(f.groupId(), f.alice(), "Dinner", 90_000L, f.alice(),
                LocalDate.of(2026, 10, 20), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(f.alice(), null),
                        new ExpenseService.Participant(f.bob(), null)));

        assertThat(publisher.publishBatch()).isPositive();
        // At-least-once is the promise; at-least-once-per-poll-forever is a bug. The
        // published_at stamp is the only thing standing between the two.
        assertThat(publisher.publishBatch()).isZero();
    }
}
