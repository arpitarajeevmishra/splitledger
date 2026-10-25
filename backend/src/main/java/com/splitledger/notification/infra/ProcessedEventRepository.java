package com.splitledger.notification.infra;

import com.splitledger.notification.domain.Notification;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Consumer-side de-duplication (ADR-005).
 *
 * <p>Delivery is at-least-once by design, so this table is what turns that into
 * effectively-once for anything that matters. It answers one question -- "have I already
 * handled this event" -- and nothing ever reads a row back.
 *
 * <p>Hung on {@code Notification} purely because Spring Data wants a managed type; every
 * statement here is native SQL against a table with no entity of its own. The same
 * arrangement, and the same reasoning, as {@code WebhookEventRepository}: a bare
 * {@code Repository} with no {@code save()}, because {@code save()} on an assigned id is a
 * merge, and a merge is exactly how the second delivery would overwrite the first claim
 * instead of colliding with it (D-70).
 */
public interface ProcessedEventRepository extends Repository<Notification, UUID> {

    /**
     * Claim an event for a consumer, or find that it was already claimed.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the primary key the arbiter in one statement,
     * with no read-then-write window for a concurrent delivery to slip through. A redelivery
     * returns 0 and the handler does nothing, which is what "the same event twice produces
     * one notification" actually rests on.
     *
     * @return 1 if this caller claimed it, 0 if it had already been handled
     */
    @Modifying
    @Query(value = "insert into processed_events (consumer, event_id, handled_at) "
            + "values (:consumer, :eventId, :now) on conflict (consumer, event_id) do nothing",
            nativeQuery = true)
    int claim(@Param("consumer") String consumer, @Param("eventId") UUID eventId,
            @Param("now") Instant now);
}
