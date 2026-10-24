package com.splitledger.common.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A provider event that has been seen (SL-603, finding C18).
 *
 * <p>Exists so Spring Data has a managed type to hang a repository on, and for no other
 * reason: every statement against this table is native SQL. The {@code payload} and
 * {@code processed_at} columns are deliberately not mapped -- nothing reads them back
 * through JPA, and leaving JSONB unmapped avoids a type mapping that exists only to be
 * unused. Hibernate's {@code validate} checks that mapped columns exist, not the reverse.
 */
@Entity
@Table(name = "webhook_events")
class WebhookEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider", nullable = false, length = 20, updatable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 100, updatable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected WebhookEvent() {
    }
}
