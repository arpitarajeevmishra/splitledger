package com.splitledger.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An event waiting to be published (ADR-005).
 *
 * <p>This row is written by the same transaction as the business change it describes, which
 * is the entire point: a commit either produces both or neither. Publishing before commit
 * would announce a fact that then rolled back; publishing after it would lose the event if
 * the process died in the gap. The table is the gap, made durable.
 *
 * <p>{@code publishedAt} is the only mutable column. There is no attempt counter and no
 * error column: a publish that fails leaves the stamp null and the row is simply claimed
 * again next poll, which is what makes "killing the bus loses nothing" true. The cost is
 * that an event nothing can ever publish is retried forever -- see O-16.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 30, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** Nullable: an event that belongs to no group keys on its aggregate instead (V6). */
    @Column(name = "group_id", updatable = false)
    private UUID groupId;

    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Failures, not attempts: published first time means zero (V7). */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    protected OutboxEvent() {
        // Hibernate.
    }

    private OutboxEvent(UUID id, String aggregateType, UUID aggregateId, UUID groupId,
            String eventType, String payload, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.groupId = groupId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public static OutboxEvent of(String aggregateType, UUID aggregateId, UUID groupId,
            String eventType, String payload, Instant createdAt) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType, aggregateId, groupId,
                eventType, payload, createdAt);
    }

    /**
     * The event id is generated here rather than by the database, because a consumer
     * de-duplicates on it ({@code processed_events}) and the publisher must therefore
     * send the same id every time this row is retried. A default on the column would be
     * fine; a value invented at publish time would not.
     */
    public EventEnvelope envelope() {
        return new EventEnvelope(id, aggregateType, aggregateId, groupId, eventType, payload,
                createdAt);
    }

    public UUID id() {
        return id;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }

    /**
     * Count a failure, and give up once there have been enough of them.
     *
     * <p>Dead-lettering is a stamp, not a delete: the row keeps its payload, because the
     * question somebody asks later is "what was this, and why did nobody get it", and a
     * deleted row answers neither. The publisher stops claiming it, so it costs nothing but
     * the space it sits in.
     *
     * @return whether this failure was the one that gave up
     */
    public boolean failedAttempt(Instant at, int maxAttempts) {
        attempts++;
        if (attempts >= maxAttempts) {
            deadLetteredAt = at;
            return true;
        }
        return false;
    }

    public int attempts() {
        return attempts;
    }

    public Instant deadLetteredAt() {
        return deadLetteredAt;
    }
}
