package com.splitledger.common.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the key table from growing without bound (LLD 8).
 *
 * <p>Two different retentions, because they answer different questions. A COMPLETED row is
 * kept long enough to cover any retry a client might plausibly make. An IN_PROGRESS row is
 * a claim whose request never finished -- normally released by the service, so one that
 * survives means the process died mid-request, and ten minutes is already generous.
 */
@Component
public class IdempotencyPurge {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurge.class);

    private final IdempotencyRepository records;
    private final Duration retention;
    private final Duration claimTimeout;
    private final Clock clock;

    @Autowired
    IdempotencyPurge(IdempotencyRepository records,
            @Value("${app.idempotency.retention}") Duration retention,
            @Value("${app.idempotency.claim-timeout}") Duration claimTimeout) {
        this(records, retention, claimTimeout, Clock.systemUTC());
    }

    /** Visible for testing. */
    IdempotencyPurge(IdempotencyRepository records, Duration retention, Duration claimTimeout,
            Clock clock) {
        this.records = records;
        this.retention = retention;
        this.claimTimeout = claimTimeout;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.idempotency.purge-cron}")
    @Transactional
    public void purge() {
        Instant now = Instant.now(clock);
        int stale = records.deleteStaleClaims(now.minus(claimTimeout));
        int expired = records.deleteOlderThan(now.minus(retention));
        if (stale > 0 || expired > 0) {
            log.info("Purged {} stale idempotency claims and {} expired keys", stale, expired);
        }
    }
}
