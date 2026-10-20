package com.portfolio.trading;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Business metrics for the trade saga, read from the trades table when Prometheus scrapes.
 *
 *  - trading.trades{status}: how many trades ended COMPLETED or REJECTED. Only ever grows, so Prometheus can show a rate.
 *  - trading.trades.pending: trades waiting for the ledger. Normally 0 for an instant.
 *  - trading.trades.pending.oldest.age: how long the oldest one has waited. If this keeps growing, the recovery job is
 *    not finishing them (the ledger is down or the circuit breaker is open): that is the number to alert on.
 */
@Component
class TradingMetrics {

    private final JdbcClient jdbc;

    TradingMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String status : List.of("COMPLETED", "REJECTED")) {
            FunctionCounter.builder("trading.trades", this, m -> m.countByStatus(status))
                    .tag("status", status)
                    .description("Trades that reached a final status")
                    .register(registry);
        }
        Gauge.builder("trading.trades.pending", this, m -> m.countByStatus("PENDING"))
                .description("Trades still waiting for the ledger to confirm the cash movement")
                .register(registry);
        Gauge.builder("trading.trades.pending.oldest.age", this, TradingMetrics::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .description("How long the oldest pending trade has been waiting (0 when there is none)")
                .register(registry);
    }

    private double countByStatus(String status) {
        try {
            return jdbc.sql("SELECT count(*) FROM trades WHERE status = :status")
                    .param("status", status)
                    .query((rs, i) -> rs.getLong(1)).single();
        } catch (Exception e) {
            return Double.NaN;   // database unreachable: report "unknown" rather than a misleading zero
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            return jdbc.sql("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)
                    FROM trades WHERE status = 'PENDING'
                    """)
                    .query((rs, i) -> rs.getDouble(1)).single();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
