package com.splitledger.outbox.application;

import com.splitledger.outbox.infra.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * How far behind the outbox is (SL-704, ADR-005).
 *
 * <p>The metric ADR-005 said this design would need, and the only one that can tell a
 * <em>stopped</em> publisher from an idle one. Everything else looks identical in both
 * cases: no errors, no traffic, a healthy process, a passing health check -- while in one
 * of them every notification in the product quietly stopped some hours ago.
 *
 * <p>Refreshed on a schedule into an {@code AtomicLong} rather than queried inside the
 * gauge callback. A gauge is read by whatever thread is serving the scrape, with no
 * transaction and no bounded time, so a database hiccup there turns a metrics scrape into a
 * hung request -- monitoring taking down the thing it monitors.
 */
@Component
@Profile("!consumer")
public class OutboxMetrics {

    private final OutboxEventRepository events;
    private final AtomicLong lagSeconds = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final Clock clock;

    @Autowired
    OutboxMetrics(OutboxEventRepository events, MeterRegistry registry) {
        this(events, registry, Clock.systemUTC());
    }

    /** Visible for testing. */
    OutboxMetrics(OutboxEventRepository events, MeterRegistry registry, Clock clock) {
        this.events = events;
        this.clock = clock;
        registry.gauge("splitledger.outbox.lag.seconds", lagSeconds, AtomicLong::doubleValue);
        // Not a counter: it only ever goes up by itself, but it goes down when someone
        // clears the table, and a counter that decreases is a reset as far as Prometheus
        // is concerned.
        registry.gauge("splitledger.outbox.dead.lettered", deadLettered, AtomicLong::doubleValue);
    }

    @Scheduled(cron = "${app.metrics.refresh-cron}")
    @Transactional(readOnly = true)
    public void refresh() {
        Instant oldest = events.oldestUnpublished();
        // Zero when nothing is waiting, which is a true statement about lag and keeps the
        // series continuous. Leaving the previous value in place would hold the last spike
        // forever, and publishing nothing would make the alert fire on absent data.
        lagSeconds.set(oldest == null ? 0L
                : Math.max(0L, Duration.between(oldest, Instant.now(clock)).toSeconds()));
        deadLettered.set(events.countDeadLettered());
    }
}
