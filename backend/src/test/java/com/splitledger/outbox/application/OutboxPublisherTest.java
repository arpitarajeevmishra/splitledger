package com.splitledger.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.outbox.domain.EventEnvelope;
import com.splitledger.outbox.domain.OutboxEvent;
import com.splitledger.outbox.infra.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The thing the whole pattern rests on (ADR-005).
 *
 * <p>The publisher is where "no lost events and no duplicates beyond at-least-once" is
 * either true or not. Everything below is about which failure produces which outcome,
 * because an outbox that quietly stamps an event it never managed to publish is strictly
 * worse than no outbox at all -- it would look correct in every other test here.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock
    private OutboxEventRepository events;

    @Mock
    private EventPublisher bus;

    private OutboxPublisher publisher() {
        return new OutboxPublisher(events, bus, 100, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event(String type) {
        return OutboxEvent.of("expense", UUID.randomUUID(), GROUP, type, "{}",
                Instant.parse("2026-10-25T11:59:00Z"));
    }

    @Test
    void publishes_each_claimed_event_and_stamps_it() {
        OutboxEvent one = event("expense.created");
        OutboxEvent two = event("expense.voided");
        when(events.claimUnpublished(100)).thenReturn(List.of(one, two));

        assertThat(publisher().publishBatch()).isEqualTo(2);

        ArgumentCaptor<EventEnvelope> sent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(bus, org.mockito.Mockito.times(2)).publish(sent.capture());
        assertThat(sent.getAllValues()).extracting(EventEnvelope::eventType)
                .containsExactly("expense.created", "expense.voided");
        // Stamped on the entity, which is in the publisher's transaction: there is no
        // explicit save because a dirty managed entity is flushed on commit, and a save()
        // here would be the only thing proving the test rather than the code.
        assertThat(one.publishedAt()).isEqualTo(NOW);
        assertThat(two.publishedAt()).isEqualTo(NOW);
    }

    @Test
    void leaves_an_event_unstamped_when_the_bus_refuses_it() {
        OutboxEvent doomed = event("expense.created");
        when(events.claimUnpublished(100)).thenReturn(List.of(doomed));
        doThrow(new IllegalStateException("broker down")).when(bus).publish(any());

        assertThat(publisher().publishBatch()).isZero();

        // The one outcome that must never happen is a stamp on an event nobody received:
        // that is silent, permanent loss, and the next poll is what makes it merely a
        // delay instead.
        assertThat(doomed.publishedAt()).isNull();
    }

    @Test
    void one_unpublishable_event_does_not_hold_up_the_ones_behind_it() {
        OutboxEvent poison = event("expense.created");
        OutboxEvent fine = event("settlement.confirmed");
        when(events.claimUnpublished(100)).thenReturn(List.of(poison, fine));
        doThrow(new IllegalStateException("nope")).when(bus)
                .publish(org.mockito.ArgumentMatchers.argThat(
                        envelope -> envelope.eventId().equals(poison.id())));

        assertThat(publisher().publishBatch()).isEqualTo(1);

        // Letting the failure out would mark the transaction rollback-only and discard
        // this stamp too, so a single bad event would wedge the queue permanently.
        assertThat(poison.publishedAt()).isNull();
        assertThat(fine.publishedAt()).isEqualTo(NOW);
    }

    @Test
    void claims_no_more_than_the_configured_batch_size() {
        when(events.claimUnpublished(7)).thenReturn(List.of(event("expense.created")));

        assertThat(new OutboxPublisher(events, bus, 7, Clock.fixed(NOW, ZoneOffset.UTC))
                .publishBatch()).isEqualTo(1);

        // The batch is also how many rows one poll holds a lock over, so a hardcoded size
        // here would silently override the knob that exists to bound that.
        verify(events).claimUnpublished(7);
    }

    @Test
    void does_not_touch_the_bus_when_there_is_nothing_to_send() {
        when(events.claimUnpublished(100)).thenReturn(List.of());

        assertThat(publisher().publishBatch()).isZero();

        // Polls once a second forever: a bus call per empty poll would be a connection
        // held open and a metric that never reads zero.
        verify(bus, never()).publish(any());
    }
}
