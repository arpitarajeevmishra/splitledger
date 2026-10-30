package com.splitledger.outbox.application;

import com.splitledger.outbox.domain.OutboxEvent;
import com.splitledger.outbox.infra.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox (ADR-005).
 *
 * <p>{@code @Profile("!consumer")} because one image runs in two roles. Both roles would
 * otherwise poll the same table, and while {@code SKIP LOCKED} makes that safe, it also
 * makes it pointless: the consumer role exists to handle events, not to compete for the
 * right to publish them.
 *
 * <p>The claim, the publish and the stamp are one transaction. That ordering is the whole
 * guarantee, and it is worth being exact about which failure does what:
 *
 * <ul>
 *   <li><b>Publish throws</b> -- the stamp is skipped, the row stays unpublished, and the
 *       next poll claims it again. Nothing is lost.
 *   <li><b>The process dies after publishing, before commit</b> -- the stamp is rolled
 *       back and the event is published a second time later. That is the at-least-once
 *       this design promises, and why {@link EventHandler} must be idempotent. It is not
 *       a bug to be fixed by moving the publish outside the transaction: that trade is
 *       duplicates for silent loss, which is the worse half.
 * </ul>
 *
 * <p>A failure is caught per event rather than per batch, so one event nobody can publish
 * does not hold up every event behind it.
 */
@Component
@Profile("!consumer")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository events;
    private final EventPublisher bus;
    private final int batchSize;
    private final int maxAttempts;
    private final Clock clock;

    @Autowired
    OutboxPublisher(OutboxEventRepository events, EventPublisher bus,
            @Value("${app.outbox.batch-size}") int batchSize,
            @Value("${app.outbox.max-attempts}") int maxAttempts) {
        this(events, bus, batchSize, maxAttempts, Clock.systemUTC());
    }

    /** Visible for testing. */
    OutboxPublisher(OutboxEventRepository events, EventPublisher bus, int batchSize,
            int maxAttempts, Clock clock) {
        this.events = events;
        this.bus = bus;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    /**
     * @return how many events were published, which is what a test and a metric both want
     *     and what a void method would make either of them guess at
     */
    @Scheduled(cron = "${app.outbox.poll-cron}")
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> claimed = events.claimUnpublished(batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now(clock);
        int published = 0;
        for (OutboxEvent event : claimed) {
            try {
                bus.publish(event.envelope());
                event.markPublished(now);
                published++;
            } catch (RuntimeException failure) {
                // Deliberately swallowed: letting this out would mark the transaction
                // rollback-only and discard the stamps of every event that did publish,
                // turning one bad event into a batch that can never drain.
                if (event.failedAttempt(now, maxAttempts)) {
                    // Loud, because this is the one outbox outcome a person has to look at:
                    // the event is now nobody's, and nothing will retry it.
                    log.error("outbox: giving up on event {} after {} attempts ({})",
                            event.id(), event.attempts(), failure.toString());
                } else {
                    log.warn("outbox: could not publish event {} (attempt {} of {}: {}),"
                            + " will retry", event.id(), event.attempts(), maxAttempts,
                            failure.toString());
                }
            }
        }
        log.debug("outbox: published {} of {} claimed", published, claimed.size());
        return published;
    }
}
