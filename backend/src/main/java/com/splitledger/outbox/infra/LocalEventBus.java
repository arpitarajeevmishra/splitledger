package com.splitledger.outbox.infra;

import com.splitledger.outbox.application.EventHandler;
import com.splitledger.outbox.application.EventPublisher;
import com.splitledger.outbox.domain.EventEnvelope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The bus the deployed instance actually runs (ADR-015).
 *
 * <p>No broker, and the guarantees are unaffected, because they never lived in one: the
 * durability is {@code outbox_events} and the de-duplication is {@code processed_events}.
 * What a broker would add here, at this scale, is two more processes that can fail to come
 * back after a restart.
 *
 * <p>Handlers run <b>inside the publisher's transaction</b>, synchronously. That is the
 * design, not a shortcut: a handler that throws rolls back the {@code published_at} stamp
 * along with its own work, so the event is redelivered. Handing the event to another thread
 * after commit would mark it published and then lose it if that thread died -- the exact
 * failure the outbox was built to rule out.
 *
 * <p>The cost is that a slow handler slows the poll, and that a handler and the publisher
 * share a transaction and therefore a fate. Both are acceptable for in-process delivery of
 * a handful of events; neither survives a second app instance, which is when the Kafka
 * implementation stops being a test fixture.
 */
@Component
// Anything that is not exactly "true" means this one, including the empty string an unset
// OUTBOX_KAFKA_ENABLED= line in a .env produces. ConditionalOnProperty would treat that
// empty value as neither "false" nor absent, leave both publishers unbuilt, and fail the
// context with a missing-bean error pointing at the scheduler rather than at the .env --
// which is exactly how D-115 went the first time.
@ConditionalOnExpression("!'${app.outbox.kafka.enabled:false}'.trim().equalsIgnoreCase('true')")
public class LocalEventBus implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalEventBus.class);

    private final List<EventHandler> handlers;

    LocalEventBus(List<EventHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void publish(EventEnvelope event) {
        for (EventHandler handler : handlers) {
            if (handler.handles(event.eventType())) {
                handler.handle(event);
            }
        }
        // Not a warning when nothing handled it: most events exist to be recorded, and a
        // bus that complained about every unsubscribed event type would train people to
        // ignore it.
        log.trace("local bus: delivered {} to {} handler(s)", event.eventType(), handlers.size());
    }
}
