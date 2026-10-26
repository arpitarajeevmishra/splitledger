package com.splitledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.splitledger.ledger.infra.LedgerPostingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The nightly check that the ledger still holds its one invariant (SL-705, ADR-003).
 */
@ExtendWith(MockitoExtension.class)
class LedgerReconciliationTest {

    private static final String GAUGE = "splitledger.ledger.unbalanced.transactions";
    private static final UUID TXN = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Mock
    private LedgerPostingRepository postings;

    private final MeterRegistry registry = new SimpleMeterRegistry();

    private LedgerReconciliation job() {
        return new LedgerReconciliation(postings, registry);
    }

    @Test
    void reports_nothing_wrong_with_a_sound_ledger() {
        when(postings.unbalancedTransactions()).thenReturn(List.of());

        assertThat(job().reconcile()).isZero();
        assertThat(registry.get(GAUGE).gauge().value()).isZero();
    }

    @Test
    void names_the_transactions_that_do_not_balance() {
        when(postings.unbalancedTransactions())
                .thenReturn(List.<Object[]>of(new Object[] {TXN, 500L}));

        assertThat(job().reconcile()).isEqualTo(1L);
        assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void catches_what_the_global_drift_sum_cannot() {
        // The whole reason this job exists beside the per-minute gauge. One transaction
        // 500 paise short and another 500 over sum to zero, so the drift metric reads a
        // contented zero while two groups' balances are both wrong. Asking per transaction
        // is what makes them visible.
        when(postings.unbalancedTransactions()).thenReturn(List.<Object[]>of(
                new Object[] {TXN, -500L},
                new Object[] {UUID.randomUUID(), 500L}));

        assertThat(job().reconcile()).isEqualTo(2L);
    }

    @Test
    void the_gauge_falls_back_to_zero_once_the_books_are_fixed() {
        LedgerReconciliation job = job();
        when(postings.unbalancedTransactions())
                .thenReturn(List.<Object[]>of(new Object[] {TXN, 500L}));
        job.reconcile();

        when(postings.unbalancedTransactions()).thenReturn(List.of());
        job.reconcile();

        // A latching gauge leaves a critical alert firing after the problem is resolved,
        // which is how people learn to ignore it.
        assertThat(registry.get(GAUGE).gauge().value()).isZero();
    }

    @Test
    void survives_a_ledger_broken_in_more_places_than_it_will_print() {
        List<Object[]> many = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> new Object[] {UUID.randomUUID(), (long) i + 1})
                .toList();
        when(postings.unbalancedTransactions()).thenReturn(many);

        // The count is the whole truth even though the log is truncated; a job that
        // reported only what it printed would understate a catastrophe as twenty problems.
        assertThat(job().reconcile()).isEqualTo(50L);
        assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(50.0);
    }
}
