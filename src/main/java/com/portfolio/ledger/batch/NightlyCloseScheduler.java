package com.portfolio.ledger.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Closes the previous day on a schedule. Disabled unless ledger.nightly-close.cron is set ("-" means off).
 * If several servers run this, only one wins for a given date: the others get a conflict and skip.
 * A conflict is not an error here.
 */
@Component
class NightlyCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(NightlyCloseScheduler.class);

    private final NightlyCloseService service;
    private final Clock clock;

    NightlyCloseScheduler(NightlyCloseService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${ledger.nightly-close.cron:-}", zone = "UTC")
    void closeYesterday() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        try {
            NightlyCloseResult result = service.run(yesterday, false);
            log.info("Nightly close for {}: {} accounts, {} mismatches", yesterday, result.accountsProcessed(), result.mismatches());
        } catch (NightlyCloseConflictException e) {
            log.info("Nightly close for {} skipped: {}", yesterday, e.getMessage());
        }
    }
}
