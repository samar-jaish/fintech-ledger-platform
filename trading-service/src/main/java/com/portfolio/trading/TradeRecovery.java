package com.portfolio.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Finishes trades left PENDING because the ledger was unreachable (or this service stopped mid-trade). It asks the ledger
 * again with the same idempotency key, which is safe: the ledger applies a transfer at most once.
 */
@Component
@EnableScheduling
public class TradeRecovery {

    private static final Logger log = LoggerFactory.getLogger(TradeRecovery.class);
    private static final int BATCH = 50;

    private final TradingRepository trades;
    private final TradeService service;
    private final Clock clock;
    private final long minAgeMs;

    TradeRecovery(TradingRepository trades, TradeService service, Clock clock,
                  @Value("${trading.recovery.min-age-ms:15000}") long minAgeMs) {
        this.trades = trades;
        this.service = service;
        this.clock = clock;
        this.minAgeMs = minAgeMs;
    }

    @Scheduled(initialDelayString = "${trading.recovery.initial-delay-ms:10000}",
               fixedDelayString = "${trading.recovery.interval-ms:10000}")
    public void recoverPending() {
        List<Trade> pending = trades.findPendingBefore(clock.instant().minusMillis(minAgeMs), BATCH);
        for (Trade trade : pending) {
            try {
                Trade after = service.drive(trade);
                log.info("Recovery: trade {} is now {}", trade.id(), after.status());
            } catch (RuntimeException e) {
                log.warn("Recovery could not finish trade {}: {}", trade.id(), e.toString());
            }
        }
    }
}
