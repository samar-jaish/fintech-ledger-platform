package com.portfolio.ledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Two numbers that say whether the outbox relay is keeping up: how many events are waiting to be published, and how
 * long the oldest one has been waiting. Both should sit near zero. If they grow, events are piling up in the database
 * (Kafka down, relay switched off, or the relay failing) and the notification service is falling behind.
 *
 * They are gauges read from the database when Prometheus scrapes, so they show the real state even after a restart.
 * Both queries use the partial index on unpublished rows, so they stay cheap.
 */
@Component
class OutboxMetrics {

    private final JdbcClient jdbc;

    OutboxMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("ledger.outbox.pending", this, OutboxMetrics::pending)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
        Gauge.builder("ledger.outbox.oldest.pending.age", this, OutboxMetrics::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .description("How long the oldest unpublished outbox event has been waiting (0 when there is none)")
                .register(registry);
    }

    private double pending() {
        try {
            return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                    .query((rs, i) -> rs.getLong(1)).single();
        } catch (Exception e) {
            return Double.NaN;   // database unreachable: report "unknown" rather than a misleading zero
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            return jdbc.sql("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)
                    FROM outbox_events WHERE published_at IS NULL
                    """)
                    .query((rs, i) -> rs.getDouble(1)).single();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
