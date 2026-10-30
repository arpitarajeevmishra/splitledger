package com.splitledger.ledger.application;

import com.splitledger.ledger.infra.LedgerPostingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watches the one arithmetic promise the whole product rests on (SL-704, ADR-003).
 *
 * <p>Every ledger transaction's postings sum to zero, so every posting ever written sums to
 * zero. This measures that. A non-zero value does not mean some balance looks odd -- it
 * means the ledger has lost the property every number in the application is derived from,
 * and nothing can be trusted until someone finds out why.
 *
 * <p>A metric rather than a test, because a test proves the property held for rows a test
 * wrote. This watches the rows real people made.
 */
@Component
public class LedgerDriftMetrics {

    private static final Logger log = LoggerFactory.getLogger(LedgerDriftMetrics.class);

    private final LedgerPostingRepository postings;
    private final AtomicLong drift = new AtomicLong();

    LedgerDriftMetrics(LedgerPostingRepository postings, MeterRegistry registry) {
        this.postings = postings;
        registry.gauge("splitledger.ledger.reconciliation.drift.paise", drift,
                AtomicLong::doubleValue);
    }

    @Scheduled(cron = "${app.metrics.refresh-cron}")
    @Transactional(readOnly = true)
    public void refresh() {
        long current = postings.reconciliationDrift();
        drift.set(current);
        if (current != 0L) {
            // Logged as well as measured. The alert needs a scrape, a rule and somewhere to
            // send it; this needs somebody to read one line. Given what a non-zero value
            // means, it should be impossible to miss through either route.
            log.error("LEDGER DRIFT: postings sum to {} paise, which must be zero (ADR-003)",
                    current);
        }
    }
}
