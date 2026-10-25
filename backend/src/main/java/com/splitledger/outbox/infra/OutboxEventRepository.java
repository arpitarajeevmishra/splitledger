package com.splitledger.outbox.infra;

import com.splitledger.outbox.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The publisher's end of the outbox.
 *
 * <p>{@code JpaRepository} here, unlike the webhook table: this one is written by business
 * transactions through {@code save()} on a brand-new row, and read back as objects so the
 * publisher can stamp them. There is no assigned-identifier merge hazard, because nothing
 * ever saves an event twice.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Take a batch of unpublished events, and lock them for the length of this transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what lets a second publisher exist without a
     * second design (ADR-005): it walks past the rows this one is holding instead of
     * queueing behind them, so two instances divide the backlog rather than serialising on
     * it. Plain {@code FOR UPDATE} would make a second publisher pure overhead, and no lock
     * at all would publish everything twice, every poll, as fast as both could go.
     *
     * <p>Oldest first, because the events of one group have to reach a consumer in the
     * order they were written, and {@code created_at} is the order they were written in.
     *
     * <p>Dead letters are excluded here rather than filtered afterwards, so that an event
     * the publisher has given up on stops consuming a slot in every batch forever (V7).
     *
     * <p>Native because JPQL has no way to say {@code SKIP LOCKED}: the JPA lock hints
     * offer {@code PESSIMISTIC_WRITE} and a timeout, and a timeout is the opposite of what
     * is wanted here -- waiting, then failing, rather than moving on to work nobody holds.
     */
    @Query(value = "select * from outbox_events where published_at is null "
            + "and dead_lettered_at is null "
            + "order by created_at limit :max for update skip locked",
            nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("max") int max);

    /**
     * When the oldest thing still waiting was written, or null when nothing is waiting.
     *
     * <p>The input to {@code outbox_lag_seconds}, which is the only metric that can tell a
     * stopped publisher from an idle one. Both look identical in every other signal: no
     * errors, no traffic, a healthy process -- and in one of them every notification in the
     * product has silently stopped.
     *
     * <p>Dead letters are excluded. They are not waiting; they have been given up on, and
     * counting them would park the lag at the age of the oldest failure forever.
     */
    @Query(value = "select min(created_at) from outbox_events "
            + "where published_at is null and dead_lettered_at is null",
            nativeQuery = true)
    Instant oldestUnpublished();

    @Query(value = "select count(*) from outbox_events where dead_lettered_at is not null",
            nativeQuery = true)
    long countDeadLettered();
}
