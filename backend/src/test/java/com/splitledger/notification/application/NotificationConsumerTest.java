package com.splitledger.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.expense.application.ExpenseEvents;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.infra.GroupRepository;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import com.splitledger.notification.domain.Notification;
import com.splitledger.notification.domain.NotificationType;
import com.splitledger.notification.infra.NotificationRepository;
import com.splitledger.notification.infra.ProcessedEventRepository;
import com.splitledger.outbox.domain.EventEnvelope;
import com.splitledger.settlement.application.SettlementEvents;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer, and the one thing it must never get wrong (SL-702).
 *
 * <p>Delivery is at-least-once by design, so "the same event twice produces one
 * notification" is not a nicety -- it is the only reason the bell is not a source of
 * duplicates every time a deploy interrupts a poll. Most of what follows is about who ends
 * up being told, because a notification sent to the wrong person is worse than none.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationConsumerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

    private final ObjectMapper json = JsonMapper.builder().build();

    @Mock
    private ProcessedEventRepository processed;
    @Mock
    private NotificationRepository notifications;
    @Mock
    private GroupRepository groups;
    @Mock
    private UserRepository users;

    private NotificationConsumer consumer() {
        return new NotificationConsumer(processed, notifications, groups, users, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EventEnvelope envelope(String type, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), "expense", UUID.randomUUID(), GROUP, type,
                json.writeValueAsString(payload), NOW);
    }

    private EventEnvelope expenseAdded(UUID actor, List<UUID> participants) {
        return envelope(ExpenseEvents.CREATED, new ExpenseEvents.Created(UUID.randomUUID(),
                GROUP, actor, actor, "Dinner", 90_000L, participants));
    }

    private List<Notification> saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    /** The claim succeeds: this delivery is the first. */
    private void firstDelivery() {
        when(processed.claim(any(), any(), any())).thenReturn(1);
    }

    @Test
    void tells_everyone_who_shared_the_expense_except_whoever_added_it() {
        firstDelivery();

        consumer().handle(expenseAdded(ALICE, List.of(ALICE, BOB, CARA)));

        assertThat(saved()).extracting(Notification::userId).containsExactly(BOB, CARA);
        // A bell that lit up for your own action is a bell people learn to ignore, and the
        // actor is the one person in the group who already knows.
        assertThat(saved()).extracting(Notification::userId).doesNotContain(ALICE);
        assertThat(saved()).allSatisfy(notification ->
                assertThat(notification.type()).isEqualTo(NotificationType.EXPENSE_ADDED));
    }

    @Test
    void a_redelivered_event_produces_no_second_notification() {
        // The claim returns 0: processed_events already holds this event id, because a
        // previous delivery handled it and the publisher died before stamping the row.
        when(processed.claim(any(), any(), any())).thenReturn(0);

        consumer().handle(expenseAdded(ALICE, List.of(ALICE, BOB)));

        // Not "one notification instead of two" -- none at all, because the first delivery
        // already wrote them. This is the whole of the at-least-once contract being made
        // survivable, and without it every interrupted poll duplicates somebody's bell.
        verify(notifications, never()).save(any());
    }

    @Test
    void claims_the_event_before_writing_anything() {
        firstDelivery();

        consumer().handle(expenseAdded(ALICE, List.of(ALICE, BOB)));

        // Order matters: writing first and claiming afterwards leaves a window in which a
        // concurrent redelivery writes the same notifications before either claim lands.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(processed, notifications);
        order.verify(processed).claim(eq("notifications"), any(), eq(NOW));
        order.verify(notifications).save(any());
    }

    @Test
    void does_not_tell_one_person_twice_about_one_expense() {
        firstDelivery();

        // A participant list is not guaranteed distinct, and two rows for one person is
        // two bell entries for one event.
        consumer().handle(expenseAdded(ALICE, List.of(BOB, BOB, CARA)));

        assertThat(saved()).extracting(Notification::userId).containsExactly(BOB, CARA);
    }

    @Test
    void tells_the_payer_when_the_payee_confirms_their_cash() {
        firstDelivery();

        consumer().handle(envelope(SettlementEvents.CONFIRMED,
                new SettlementEvents.Confirmed(UUID.randomUUID(), GROUP, ALICE, BOB, 50_000L,
                        "CASH", BOB)));

        // Alice paid, Bob said it arrived. Bob does not need telling what he just did.
        assertThat(saved()).extracting(Notification::userId).containsExactly(ALICE);
    }

    @Test
    void tells_both_parties_when_a_gateway_confirms_it() {
        firstDelivery();

        consumer().handle(envelope(SettlementEvents.CONFIRMED,
                new SettlementEvents.Confirmed(UUID.randomUUID(), GROUP, ALICE, BOB, 50_000L,
                        "RAZORPAY", null)));

        // Nobody acted, so it is news to both of them -- and the null is the difference
        // between that and silently excluding whoever happened to be first in the record.
        assertThat(saved()).extracting(Notification::userId).containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    void freezes_the_group_and_actor_names_into_the_message() {
        firstDelivery();
        when(groups.findById(GROUP)).thenReturn(Optional.of(
                ExpenseGroup.create("Flat 402", "INR", ALICE, NOW)));
        when(users.findById(ALICE)).thenReturn(Optional.of(
                User.register("alice@example.com", "Alice Ray", "hash", NOW)));

        consumer().handle(expenseAdded(ALICE, List.of(ALICE, BOB)));

        // Written into the payload, not resolved when the bell is read: a notification is
        // a message about something that happened, so it should still say Flat 402 after
        // the group is renamed, and rendering it should not cost a join per row.
        assertThat(saved()).singleElement().satisfies(notification ->
                assertThat(notification.payload())
                        .contains("\"groupName\":\"Flat 402\"")
                        .contains("\"actorName\":\"Alice Ray\""));
    }

    @Test
    void ignores_event_types_it_did_not_ask_for() {
        // handles() is the filter the bus applies; these two must agree, or the consumer
        // either misses events or is handed ones it cannot parse.
        NotificationConsumer consumer = consumer();
        assertThat(consumer.handles(ExpenseEvents.CREATED)).isTrue();
        assertThat(consumer.handles(ExpenseEvents.VOIDED)).isTrue();
        assertThat(consumer.handles(SettlementEvents.CONFIRMED)).isTrue();
        assertThat(consumer.handles("member.joined")).isFalse();
    }
}
