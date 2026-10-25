package com.splitledger.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing somebody should be told about.
 *
 * <p>The payload is a frozen message, not a view of live rows. It carries the group and
 * actor names as they were when the event happened, so the bell does not need a join per
 * row and does not quietly rewrite history when a group is renamed -- "Priya added Dinner
 * in Flat 402" should still say Flat 402 after the group becomes Flat 403, because that is
 * what the notification was about.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // Hibernate.
    }

    private Notification(UUID id, UUID userId, NotificationType type, String payload,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public static Notification of(UUID userId, NotificationType type, String payload,
            Instant createdAt) {
        return new Notification(UUID.randomUUID(), userId, type, payload, createdAt);
    }

    /**
     * Marking a read notification read again is not an error and not a write.
     *
     * <p>The bell is cleared by a client that may well send the same call twice -- two tabs,
     * a retry, a double tap. Refusing the second would turn an idempotent gesture into an
     * error dialog, and overwriting the timestamp would move a read receipt that already
     * happened.
     */
    public void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public NotificationType type() {
        return type;
    }

    public String payload() {
        return payload;
    }

    public Instant readAt() {
        return readAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
