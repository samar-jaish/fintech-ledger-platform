package com.portfolio.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class NotificationRepository {

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true if stored, false if this event id was already handled (a duplicate delivery). */
    boolean saveIfNew(UUID eventId, TransferCompletedEvent e, String message) {
        return jdbc.sql("""
                INSERT INTO notifications (event_id, transaction_id, from_account, to_account, amount, message)
                VALUES (:eventId, :tx, :from, :to, :amount, :message)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("eventId", eventId).param("tx", e.transactionId())
                .param("from", e.fromAccountId()).param("to", e.toAccountId())
                .param("amount", e.amount()).param("message", message)
                .update() == 1;
    }

    long countForTransaction(UUID transactionId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE transaction_id = :tx")
                .param("tx", transactionId).query(Long.class).single();
    }
}
