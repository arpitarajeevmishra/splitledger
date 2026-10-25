package com.splitledger.outbox.application;

import com.splitledger.outbox.domain.OutboxEvent;
import com.splitledger.outbox.infra.OutboxEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * How a business transaction says that something happened.
 *
 * <p>Call this from inside the transaction doing the write, never after it. The guarantee
 * in ADR-005 is that the business rows and the event row share a commit, and it is this
 * call site -- not the table -- that decides whether that is true.
 *
 * <p>{@code Propagation.MANDATORY} enforces exactly that: recording an event with no
 * transaction in progress is not a degraded mode to be tolerated, it is the dual-write bug
 * the outbox exists to prevent, and it fails here rather than looking fine until a rollback
 * leaves an event describing something that never happened.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository events;
    private final ObjectMapper json;
    private final Clock clock;

    OutboxRecorder(OutboxEventRepository events, ObjectMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param groupId the group this event belongs to, and the key it will be published
     *     under; null only for an event that genuinely belongs to no group
     * @param payload serialised here rather than by the caller, so that a payload which
     *     cannot be written fails the business transaction instead of being discovered by
     *     the publisher minutes later, with the write already committed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, UUID aggregateId, UUID groupId, String eventType,
            Object payload) {
        events.save(OutboxEvent.of(aggregateType, aggregateId, groupId, eventType,
                json.writeValueAsString(payload), clock.instant()));
    }
}
