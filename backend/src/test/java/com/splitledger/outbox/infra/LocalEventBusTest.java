package com.splitledger.outbox.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.outbox.application.EventHandler;
import com.splitledger.outbox.domain.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bus the deployed instance runs (ADR-015).
 */
class LocalEventBusTest {

    private static EventEnvelope envelope(String type) {
        return new EventEnvelope(UUID.randomUUID(), "expense", UUID.randomUUID(),
                UUID.randomUUID(), type, "{}", Instant.parse("2026-10-25T12:00:00Z"));
    }

    /** Records what it was given, so a test can ask what reached it. */
    private static final class Recording implements EventHandler {
        private final String wanted;
        private final List<String> seen = new ArrayList<>();

        Recording(String wanted) {
            this.wanted = wanted;
        }

        @Override
        public boolean handles(String eventType) {
            return wanted.equals(eventType);
        }

        @Override
        public void handle(EventEnvelope event) {
            seen.add(event.eventType());
        }
    }

    @Test
    void delivers_only_to_handlers_that_asked_for_that_event() {
        Recording expenses = new Recording("expense.created");
        Recording settlements = new Recording("settlement.confirmed");

        new LocalEventBus(List.of(expenses, settlements)).publish(envelope("expense.created"));

        assertThat(expenses.seen).containsExactly("expense.created");
        // A bus that ignored handles() would work until two consumers existed, and then
        // deliver every event to both -- which a handler is entitled to treat as a real
        // event and act on.
        assertThat(settlements.seen).isEmpty();
    }

    @Test
    void delivers_to_every_interested_handler() {
        Recording one = new Recording("expense.created");
        Recording two = new Recording("expense.created");

        new LocalEventBus(List.of(one, two)).publish(envelope("expense.created"));

        assertThat(one.seen).hasSize(1);
        assertThat(two.seen).hasSize(1);
    }

    @Test
    void lets_a_failing_handler_through_so_the_event_is_not_marked_published() {
        EventHandler broken = new EventHandler() {
            @Override
            public boolean handles(String eventType) {
                return true;
            }

            @Override
            public void handle(EventEnvelope event) {
                throw new IllegalStateException("handler blew up");
            }
        };

        // Swallowing this is the subtle way to lose an event: the publisher would stamp
        // published_at on something no consumer ever processed, and nothing would retry
        // it. In-process delivery only keeps the at-least-once promise because a handler
        // failure takes the stamp down with it.
        assertThatThrownBy(() -> new LocalEventBus(List.of(broken)).publish(envelope("any")))
                .isInstanceOf(IllegalStateException.class);
    }
}
