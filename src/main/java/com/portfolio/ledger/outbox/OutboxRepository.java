package com.portfolio.ledger.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks up to {@code limit} unpublished events, oldest first. SKIP LOCKED means a second relay instance
     * takes different rows instead of waiting, so running several instances is safe.
     */
    List<OutboxEvent> claimUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, i) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload")))
                .list();
    }

    void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = :id").param("id", id).update();
    }

    /** Returns true the first time an event id is seen by this consumer, false on a redelivery. */
    boolean firstTimeSeen(UUID eventId, String consumer) {
        return jdbc.sql("""
                INSERT INTO processed_events (event_id, consumer) VALUES (:id, :consumer)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("id", eventId).param("consumer", consumer).update() == 1;
    }
}
