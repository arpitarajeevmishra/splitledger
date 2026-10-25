package com.splitledger.notification.application;

import com.splitledger.expense.application.ExpenseEvents;
import com.splitledger.group.infra.GroupRepository;
import com.splitledger.identity.infra.UserRepository;
import com.splitledger.notification.domain.Notification;
import com.splitledger.notification.domain.NotificationType;
import com.splitledger.notification.infra.NotificationRepository;
import com.splitledger.notification.infra.ProcessedEventRepository;
import com.splitledger.outbox.application.EventHandler;
import com.splitledger.outbox.domain.EventEnvelope;
import com.splitledger.settlement.application.SettlementEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns events into things people are told about (SL-702).
 *
 * <p>Runs inside the publisher's transaction, so the notifications, the
 * {@code processed_events} claim and the event's {@code published_at} stamp all commit
 * together. A failure here takes the stamp with it and the event is redelivered, which is
 * what makes the de-duplication below load-bearing rather than decorative.
 *
 * <p><b>Nobody is notified of their own action.</b> A bell that lit up when you added an
 * expense would be a bell people learn to ignore, and the actor is the one person in the
 * group who already knows.
 */
@Component
public class NotificationConsumer implements EventHandler {

    /** The key half of {@code processed_events}; changing it replays history. */
    private static final String CONSUMER = "notifications";

    private final ProcessedEventRepository processed;
    private final NotificationRepository notifications;
    private final GroupRepository groups;
    private final UserRepository users;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    NotificationConsumer(ProcessedEventRepository processed, NotificationRepository notifications,
            GroupRepository groups, UserRepository users, ObjectMapper json) {
        this(processed, notifications, groups, users, json, Clock.systemUTC());
    }

    /** Visible for testing. */
    NotificationConsumer(ProcessedEventRepository processed, NotificationRepository notifications,
            GroupRepository groups, UserRepository users, ObjectMapper json, Clock clock) {
        this.processed = processed;
        this.notifications = notifications;
        this.groups = groups;
        this.users = users;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public boolean handles(String eventType) {
        return ExpenseEvents.CREATED.equals(eventType)
                || ExpenseEvents.VOIDED.equals(eventType)
                || SettlementEvents.CONFIRMED.equals(eventType);
    }

    /**
     * In a transaction of its own, and that is not incidental.
     *
     * <p>The publisher catches a handler failure per event so that one bad event cannot
     * wedge the batch. Without {@code REQUIRES_NEW} that catch would be a silent
     * correctness bug: a handler that claimed the event, wrote two notifications of three
     * and then threw would have all of that committed by the publisher's transaction, and
     * the claim would stop it ever being retried. The event would be permanently recorded
     * as handled and permanently half-handled.
     *
     * <p>With its own transaction the claim and the notifications roll back together, the
     * publisher declines to stamp {@code published_at}, and the next poll delivers it
     * again -- cleanly, to a consumer that has no record of having seen it.
     *
     * <p>The cost is a second connection held for the length of the handler while the
     * publisher's transaction is suspended. Acceptable for a batch of in-process handlers;
     * it would need thinking about again if a handler ever did anything slow.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(EventEnvelope event) {
        Instant now = Instant.now(clock);
        // Claimed before anything is written. A redelivery -- which the outbox promises
        // will happen eventually -- inserts nothing and returns here, which is the whole
        // of "the same event twice produces one notification". ON CONFLICT DO NOTHING
        // leaves no read-then-write window for a second delivery to slip through.
        if (processed.claim(CONSUMER, event.eventId(), now) == 0) {
            return;
        }

        switch (event.eventType()) {
            case ExpenseEvents.CREATED -> {
                ExpenseEvents.Created created =
                        json.readValue(event.payload(), ExpenseEvents.Created.class);
                tell(created.participants(), created.actorId(), NotificationType.EXPENSE_ADDED,
                        message(created.groupId(), created.actorId(), created.description(),
                                created.amountPaise()),
                        now);
            }
            case ExpenseEvents.VOIDED -> {
                ExpenseEvents.Voided voided =
                        json.readValue(event.payload(), ExpenseEvents.Voided.class);
                tell(voided.participants(), voided.actorId(), NotificationType.EXPENSE_VOIDED,
                        message(voided.groupId(), voided.actorId(), voided.description(),
                                voided.amountPaise()),
                        now);
            }
            case SettlementEvents.CONFIRMED -> {
                SettlementEvents.Confirmed confirmed =
                        json.readValue(event.payload(), SettlementEvents.Confirmed.class);
                // Both parties, minus whoever confirmed it. When a gateway confirmed it
                // nobody did, and it is news to both of them.
                tell(List.of(confirmed.fromUser(), confirmed.toUser()), confirmed.confirmedBy(),
                        NotificationType.SETTLEMENT_CONFIRMED,
                        message(confirmed.groupId(), confirmed.confirmedBy(), null,
                                confirmed.amountPaise()),
                        now);
            }
            default -> {
                // handles() is the filter; reaching here means the two disagree.
                throw new IllegalStateException("not a notifiable event: " + event.eventType());
            }
        }
    }

    /**
     * @param actorId excluded from the recipients, and null when nobody acted
     */
    private void tell(List<UUID> recipients, UUID actorId, NotificationType type,
            NotificationMessage message, Instant now) {
        // A set, because a participant list is not guaranteed to be distinct and two rows
        // for one person is two bell entries for one event.
        Set<UUID> distinct = new LinkedHashSet<>(recipients);
        distinct.removeIf(userId -> Objects.equals(userId, actorId));
        String payload = json.writeValueAsString(message);
        for (UUID userId : distinct) {
            notifications.save(Notification.of(userId, type, payload, now));
        }
    }

    /**
     * The names are read once here and frozen into the payload.
     *
     * <p>A notification is a message about something that happened, not a live view: it
     * should still say "Flat 402" after the group is renamed, and it should not cost a join
     * per row to render a bell. The alternative -- ids only, resolved at read time -- also
     * means the bell cannot render a group the reader has since left.
     */
    private NotificationMessage message(UUID groupId, UUID actorId, String description,
            long amountPaise) {
        String groupName = groups.findById(groupId)
                .map(group -> group.name())
                .orElse(null);
        String actorName = actorId == null ? null : users.findById(actorId)
                .map(user -> user.name())
                .orElse(null);
        return new NotificationMessage(groupId, groupName, actorId, actorName, description,
                amountPaise);
    }

    /**
     * What the bell renders. Deliberately flat and deliberately denormalised.
     *
     * @param actorName null when a gateway confirmed a payment and no person did
     * @param description null for a settlement, which has no description of its own
     */
    public record NotificationMessage(UUID groupId, String groupName, UUID actorId,
            String actorName, String description, long amountPaise) {
    }
}
