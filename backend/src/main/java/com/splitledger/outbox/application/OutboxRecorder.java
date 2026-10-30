package com.splitledger.outbox.application;

import com.splitledger.outbox.domain.OutboxEvent;
import com.splitledger.outbox.infra.OutboxEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OutboxNudge nudge;
    private final Clock clock;

    /**
     * No {@code Clock} parameter, because this application has no {@code Clock} bean: every
     * service here takes the system clock and accepts a fixed one only through a
     * package-private constructor for tests. Asking Spring to inject one fails the context,
     * and fails it nowhere a unit test would look, since none of them load a context. An
     * ArchUnit rule now refuses it.
     */
    @Autowired
    OutboxRecorder(OutboxEventRepository events, ObjectMapper json, OutboxNudge nudge) {
        this(events, json, nudge, Clock.systemUTC());
    }

    /** Visible for testing. */
    OutboxRecorder(OutboxEventRepository events, ObjectMapper json, OutboxNudge nudge,
            Clock clock) {
        this.events = events;
        this.json = json;
        this.nudge = nudge;
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
        // Asks for a publish once this transaction commits, so the poll does not have to
        // be frequent. Registered rather than performed: publishing before the commit
        // would announce something that may still roll back.
        nudge.afterCommit();
    }
}
