package com.splitledger.ledger.application;

import com.splitledger.ledger.infra.LedgerPostingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks the ledger, nightly, whether it is still telling the truth (SL-705, ADR-003).
 *
 * <p><b>A stronger question than the drift gauge asks.</b> {@code LedgerDriftMetrics} sums
 * every posting in the system, which is cheap enough to run every minute and catches a net
 * imbalance. It cannot catch two equal and opposite errors: one transaction 500 paise short
 * and another 500 over sum to a contented zero while two groups' balances are both wrong.
 * This checks each transaction against the invariant it is actually supposed to hold.
 *
 * <p>Nightly rather than every minute because it is a {@code GROUP BY} over every posting
 * ever written, and because the failure it looks for is not one that a minute of delay
 * makes worse -- if the books have stopped balancing, they have been wrong since whenever
 * the bad write happened, and the only thing that matters is that somebody finds out.
 *
 * <p>It repairs nothing, deliberately. An automatic correction would post a balancing entry
 * for a cause nobody has diagnosed, which turns evidence of a bug into a tidy ledger with
 * the bug still in it.
 */
@Component
public class LedgerReconciliation {

    private static final Logger log = LoggerFactory.getLogger(LedgerReconciliation.class);

    /** Enough to diagnose; a log line per transaction would be the wrong way to find out. */
    private static final int MAX_REPORTED = 20;

    private final LedgerPostingRepository postings;
    private final AtomicLong unbalanced = new AtomicLong();

    LedgerReconciliation(LedgerPostingRepository postings, MeterRegistry registry) {
        this.postings = postings;
        registry.gauge("splitledger.ledger.unbalanced.transactions", unbalanced,
                AtomicLong::doubleValue);
    }

    /**
     * @return how many transactions do not balance, which is zero on every healthy run
     */
    @Scheduled(cron = "${app.ledger.reconciliation-cron}")
    @Transactional(readOnly = true)
    public long reconcile() {
        List<Object[]> broken = postings.unbalancedTransactions();
        unbalanced.set(broken.size());

        if (broken.isEmpty()) {
            // Logged at info even when clean. A reconciliation that only speaks up when
            // something is wrong is indistinguishable from one that has not run for a
            // month, and this is the job whose silence is least safe to assume.
            log.info("Ledger reconciliation: every transaction balances");
            return 0L;
        }

        log.error("LEDGER RECONCILIATION FAILED: {} transaction(s) do not sum to zero",
                broken.size());
        broken.stream().limit(MAX_REPORTED).forEach(row ->
                log.error("  txn {} sums to {} paise", asUuid(row[0]), row[1]));
        if (broken.size() > MAX_REPORTED) {
            log.error("  ... and {} more", broken.size() - MAX_REPORTED);
        }
        return broken.size();
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
