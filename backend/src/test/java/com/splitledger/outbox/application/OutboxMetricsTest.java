package com.splitledger.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.splitledger.outbox.infra.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The metric that separates a stopped publisher from an idle one (SL-704, ADR-005).
 *
 * <p>Both look the same everywhere else: no errors, no traffic, a healthy process, a
 * passing health check. In one of them every notification in the product stopped hours ago.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Mock
    private OutboxEventRepository events;

    private final MeterRegistry registry = new SimpleMeterRegistry();

    private OutboxMetrics metrics() {
        return new OutboxMetrics(events, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void reports_the_age_of_the_oldest_thing_still_waiting() {
        when(events.oldestUnpublished()).thenReturn(Instant.parse("2026-10-25T11:58:00Z"));
        metrics().refresh();

        assertThat(gauge("splitledger.outbox.lag.seconds")).isEqualTo(120.0);
    }

    @Test
    void reports_zero_rather_than_nothing_when_the_queue_is_empty() {
        when(events.oldestUnpublished()).thenReturn(null);
        metrics().refresh();

        // Zero is a true statement about lag and keeps the series continuous. Publishing
        // nothing would make the alert fire on absent data, and leaving the previous value
        // would hold the last spike forever.
        assertThat(gauge("splitledger.outbox.lag.seconds")).isZero();
    }

    @Test
    void never_reports_a_negative_lag() {
        // An event written by a process whose clock is a little ahead. A negative lag is
        // nonsense, and worse, it reads as "the queue is better than empty".
        when(events.oldestUnpublished()).thenReturn(Instant.parse("2026-10-25T12:00:30Z"));
        metrics().refresh();

        assertThat(gauge("splitledger.outbox.lag.seconds")).isZero();
    }

    @Test
    void counts_what_was_given_up_on() {
        when(events.oldestUnpublished()).thenReturn(null);
        when(events.countDeadLettered()).thenReturn(3L);
        metrics().refresh();

        assertThat(gauge("splitledger.outbox.dead.lettered")).isEqualTo(3.0);
    }

    @Test
    void the_lag_falls_back_to_zero_once_the_backlog_drains() {
        OutboxMetrics metrics = metrics();
        when(events.oldestUnpublished()).thenReturn(Instant.parse("2026-10-25T11:00:00Z"));
        metrics.refresh();
        assertThat(gauge("splitledger.outbox.lag.seconds")).isEqualTo(3600.0);

        when(events.oldestUnpublished()).thenReturn(null);
        metrics.refresh();

        // A gauge that only ever rose would keep an hour-old spike on the dashboard, and
        // the alert with it, long after the publisher recovered.
        assertThat(gauge("splitledger.outbox.lag.seconds")).isZero();
    }
}
