package com.splitledger.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The partition key, which is the whole of the ordering guarantee (ADR-005).
 */
class EventEnvelopeTest {

    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID EXPENSE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static EventEnvelope envelope(UUID groupId) {
        return new EventEnvelope(UUID.randomUUID(), "expense", EXPENSE, groupId,
                "expense.created", "{}", Instant.parse("2026-10-25T12:00:00Z"));
    }

    @Test
    void keys_on_the_group_so_one_group_keeps_its_order() {
        // Not the aggregate: every expense is its own aggregate, so keying on it would
        // scatter one group's events across partitions and the ordering claim -- the only
        // reason this method exists -- would be false while still looking implemented.
        assertThat(envelope(GROUP).key()).isEqualTo(GROUP.toString());
    }

    @Test
    void falls_back_to_the_aggregate_when_there_is_no_group() {
        // An account-level event has no group and should not have to invent one. Ordering
        // per-aggregate is the right guarantee for it, and the key is never null, so there
        // is no such thing as an unkeyed event to land in an arbitrary partition.
        assertThat(envelope(null).key()).isEqualTo(EXPENSE.toString());
    }
}
