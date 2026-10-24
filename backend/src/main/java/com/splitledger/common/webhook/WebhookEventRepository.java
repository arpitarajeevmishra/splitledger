package com.splitledger.common.webhook;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The record of which provider events have been seen (SL-603, finding C18).
 *
 * <p>Extends the bare {@code Repository} marker rather than {@code JpaRepository}, so
 * there is no {@code save()} on it at all. That is the point: {@code save()} on an
 * assigned identifier is a merge, and a merge is exactly how the twentieth copy of a
 * delivery would overwrite the first claim instead of colliding with it. A method that
 * must never be called is better absent than documented.
 *
 * <p>Nothing here reads an event back as an object. The table answers one question --
 * "have I already handled this delivery" -- and keeps the payload for whoever later has
 * to work out what happened.
 */
public interface WebhookEventRepository extends Repository<WebhookEvent, UUID> {

    /**
     * Claim a delivery, or discover that it was already claimed.
     *
     * <p>Native, and emphatically not {@code save()}: Spring Data's {@code save()} on an
     * assigned identifier calls {@code merge()}, which SELECTs and then UPDATEs when the
     * row exists -- so the twentieth copy of a delivery would quietly overwrite the first
     * claim and proceed to post to the ledger again. That mistake has been made in this
     * project once already (D-70) and this is the same shape of table.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the unique index the arbiter in one
     * statement. Concurrent deliveries of the same event do not race: the second INSERT
     * blocks on the first one's row until it commits, then returns 0 -- or, if the first
     * rolled back, inserts and proceeds, which is what makes a retry after a failure work.
     *
     * @return 1 if this caller claimed the delivery, 0 if someone already had it
     */
    @Modifying
    @Query(value = "insert into webhook_events "
            + "(id, provider, provider_event_id, event_type, payload, received_at, status) "
            + "values (:id, :provider, :eventId, :eventType, cast(:payload as jsonb), :now, "
            + "'RECEIVED') "
            + "on conflict (provider, provider_event_id) do nothing",
            nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("provider") String provider,
            @Param("eventId") String eventId, @Param("eventType") String eventType,
            @Param("payload") String payload, @Param("now") Instant now);

    /** Mark how it went, for whoever has to reconstruct a payment later. */
    @Modifying
    @Query(value = "update webhook_events set status = :status, processed_at = :now "
            + "where provider = :provider and provider_event_id = :eventId",
            nativeQuery = true)
    int finish(@Param("provider") String provider, @Param("eventId") String eventId,
            @Param("status") String status, @Param("now") Instant now);
}
