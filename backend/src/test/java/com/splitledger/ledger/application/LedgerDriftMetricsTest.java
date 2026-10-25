package com.splitledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.splitledger.ledger.infra.LedgerPostingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The gauge that watches double-entry itself (SL-704, ADR-003).
 */
@ExtendWith(MockitoExtension.class)
class LedgerDriftMetricsTest {

    private static final String GAUGE = "splitledger.ledger.reconciliation.drift.paise";

    @Mock
    private LedgerPostingRepository postings;

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void reports_zero_on_a_healthy_ledger() {
        when(postings.reconciliationDrift()).thenReturn(0L);
        new LedgerDriftMetrics(postings, registry).refresh();

        assertThat(registry.get(GAUGE).gauge().value()).isZero();
    }

    @Test
    void reports_the_drift_when_the_books_stop_balancing() {
        when(postings.reconciliationDrift()).thenReturn(-1L);
        new LedgerDriftMetrics(postings, registry).refresh();

        // One paisa. The size is irrelevant -- any non-zero value means the ledger has lost
        // the property every number in the product is derived from, so the alert fires on
        // "!= 0" and not on a threshold.
        assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(-1.0);
    }

    @Test
    void goes_back_to_zero_once_it_is_fixed() {
        LedgerDriftMetrics metrics = new LedgerDriftMetrics(postings, registry);
        when(postings.reconciliationDrift()).thenReturn(500L);
        metrics.refresh();

        when(postings.reconciliationDrift()).thenReturn(0L);
        metrics.refresh();

        // A latching gauge would leave a critical alert firing after the problem was
        // resolved, which is how people learn to ignore it.
        assertThat(registry.get(GAUGE).gauge().value()).isZero();
    }
}
