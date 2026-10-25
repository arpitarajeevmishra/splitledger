package com.splitledger.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.outbox.domain.EventEnvelope;
import com.splitledger.outbox.domain.OutboxEvent;
import com.splitledger.outbox.infra.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a business transaction says that something happened (ADR-005).
 */
@ExtendWith(MockitoExtension.class)
class OutboxRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID EXPENSE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private record Payload(UUID expenseId, String description, long amountPaise) {
    }

    @Mock
    private OutboxEventRepository events;

    private OutboxRecorder recorder() {
        return new OutboxRecorder(events, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EventEnvelope recorded() {
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        org.mockito.Mockito.verify(events).save(saved.capture());
        return saved.getValue().envelope();
    }

    @Test
    void writes_the_event_with_its_aggregate_group_and_type() {
        recorder().record("expense", EXPENSE, GROUP, "expense.created",
                new Payload(EXPENSE, "Dinner", 90_000L));

        EventEnvelope envelope = recorded();
        assertThat(envelope.aggregateType()).isEqualTo("expense");
        assertThat(envelope.aggregateId()).isEqualTo(EXPENSE);
        assertThat(envelope.eventType()).isEqualTo("expense.created");
        assertThat(envelope.occurredAt()).isEqualTo(NOW);
        // Carried as its own column rather than left in the payload, because the publisher
        // keys on it and an ordering guarantee that depended on every emitter spelling a
        // JSON field the same way would not be a guarantee.
        assertThat(envelope.groupId()).isEqualTo(GROUP);
    }

    @Test
    void serialises_the_payload_here_rather_than_leaving_it_to_the_publisher() {
        recorder().record("expense", EXPENSE, GROUP, "expense.created",
                new Payload(EXPENSE, "Dinner", 90_000L));

        // Written now, inside the business transaction, so a payload that cannot be
        // serialised fails the write rather than being discovered by a scheduled job
        // minutes later with the expense already committed and no event to announce it.
        assertThat(recorded().payload())
                .contains("\"description\":\"Dinner\"")
                .contains("\"amountPaise\":90000");
    }

    @Test
    void publishes_under_the_row_s_own_id_so_a_retry_is_recognisable() {
        recorder().record("expense", EXPENSE, GROUP, "expense.created",
                new Payload(EXPENSE, "Dinner", 90_000L));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        org.mockito.Mockito.verify(events).save(saved.capture());

        // The consumer de-duplicates on this id, so it has to be the row's own and the
        // same on every redelivery. An id minted when the envelope is built would be
        // fresh each poll, making every retry look like a new event and defeating
        // processed_events entirely -- while every other assertion here still passed.
        assertThat(saved.getValue().envelope().eventId()).isEqualTo(saved.getValue().id());
    }
}
