package com.splitledger.settlement.application;

import com.splitledger.settlement.domain.Settlement;
import com.splitledger.settlement.domain.SettlementStatus;
import com.splitledger.settlement.infra.SettlementRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reclaims settlements nobody finished (LLD 7.1, finding C17).
 *
 * <p>Sweeps INITIATED as well as PENDING. A settlement abandoned before any order was
 * created would otherwise sit in the group's list forever, and the person who started it
 * would keep seeing a payment they never made.
 *
 * <p>Expiring posts nothing and changes no balance -- an unconfirmed settlement never
 * moved money, so letting go of it costs nothing. And EXPIRED is not terminal: a late
 * capture can still confirm one of these, which is exactly why they are expired rather
 * than deleted.
 */
@Component
public class SettlementExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(SettlementExpirySweeper.class);

    private static final List<SettlementStatus> UNFINISHED =
            List.of(SettlementStatus.INITIATED, SettlementStatus.PENDING);

    private final SettlementRepository settlements;
    private final Clock clock;

    @Autowired
    SettlementExpirySweeper(SettlementRepository settlements) {
        this(settlements, Clock.systemUTC());
    }

    /** Visible for testing. */
    SettlementExpirySweeper(SettlementRepository settlements, Clock clock) {
        this.settlements = settlements;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.settlements.expiry-cron}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now(clock);
        List<Settlement> due = settlements.findAllByStatusInAndExpiresAtBefore(UNFINISHED, now);

        for (Settlement settlement : due) {
            // Each one goes through the aggregate rather than a bulk UPDATE, so the state
            // machine stays the only thing that decides what a settlement may become.
            settlement.expire(now);
            settlements.save(settlement);
        }
        if (!due.isEmpty()) {
            log.info("Expired {} unfinished settlements", due.size());
        }
    }
}
