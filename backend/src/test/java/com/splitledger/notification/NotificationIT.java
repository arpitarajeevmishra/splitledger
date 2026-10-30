package com.splitledger.notification;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.splitledger.notification.application.NotificationService;
import com.splitledger.notification.domain.Notification;
import com.splitledger.outbox.application.OutboxPublisher;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Event to bell, against a real database (SL-702).
 *
 * <p>Here rather than in a unit test because the de-duplication is a unique index doing the
 * work. A mocked {@code claim()} returns whatever it was told to; only Postgres can show
 * that the second insert of the same {@code (consumer, event_id)} loses and that the
 * consumer notices.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-notification-test-secret-long-enough-for-hs256",
        // Publishing is driven explicitly; a background poll draining the table mid-test
        // would make every assertion below a race.
        // A cron that never fires this century, so publishing is driven only
        // by the explicit calls below.
        "app.outbox.poll-cron=0 0 5 31 2 ?",
        // And no nudge, for the same reason: publishing has to happen
        // only where these tests ask for it, or "still unpublished" is
        // a race against a thread that commits faster than the assert.
        "app.outbox.nudge-enabled=false"
})
class NotificationIT {

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private NotificationService notifications;

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
                "alice-" + System.nanoTime() + "@example.com", "Alice Ray", "hash", now));
        User bob = users.save(User.register(
                "bob-" + System.nanoTime() + "@example.com", "Bob Singh", "hash", now));
        ExpenseGroup group = groups.save(ExpenseGroup.create("Flat 402", "INR", alice.id(), now));
        groupMembers.save(GroupMember.join(group.id(), alice.id(), Role.ADMIN, now));
        groupMembers.save(GroupMember.join(group.id(), bob.id(), Role.MEMBER, now));
        return new Fixture(group.id(), alice.id(), bob.id());
    }

    private void dinner(Fixture f) {
        expenses.record(f.groupId(), f.alice(), "Dinner", 90_000L, f.alice(),
                LocalDate.of(2026, 10, 20), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(f.alice(), null),
                        new ExpenseService.Participant(f.bob(), null)));
    }

    @Test
    void an_expense_reaches_the_other_members_bell() {
        Fixture f = fixture();
        dinner(f);

        publisher.publishBatch();

        assertThat(notifications.unread(f.bob())).isEqualTo(1);
        // Alice added it. Telling her about it would be the fastest way to teach everyone
        // to ignore the bell.
        assertThat(notifications.unread(f.alice())).isZero();
    }

    @Test
    void the_same_event_delivered_twice_produces_one_notification() {
        Fixture f = fixture();
        dinner(f);

        // Two polls. The first publishes and stamps; the second finds nothing, so the
        // handler is driven again directly through a republish of the same event -- which
        // is what a crash between publishing and committing would cause.
        publisher.publishBatch();
        publisher.publishBatch();

        assertThat(notifications.unread(f.bob())).isEqualTo(1);
    }

    @Test
    void the_bell_can_be_cleared() {
        Fixture f = fixture();
        dinner(f);
        publisher.publishBatch();
        assertThat(notifications.unread(f.bob())).isPositive();

        assertThat(notifications.markAllRead(f.bob())).isEqualTo(1);

        assertThat(notifications.unread(f.bob())).isZero();
        // Cleared, not deleted: the row stays and simply stops counting.
        assertThat(notifications.mine(f.bob(), 0, 10))
                .singleElement()
                .extracting(Notification::readAt)
                .isNotNull();
    }

    @Test
    void a_second_clear_changes_nothing() {
        Fixture f = fixture();
        dinner(f);
        publisher.publishBatch();
        notifications.markAllRead(f.bob());

        assertThat(notifications.markAllRead(f.bob())).isZero();
    }
}
