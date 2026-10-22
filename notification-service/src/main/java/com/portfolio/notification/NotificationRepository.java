package com.portfolio.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class NotificationRepository {

    private static final String COLUMNS =
            "id, transaction_id, from_account, to_account, amount, message, created_at, from_owner, to_owner";

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the notification and returns it, or returns empty if this event id was already handled (a duplicate delivery). */
    Optional<Notification> saveIfNew(UUID eventId, TransferCompletedEvent e, String message) {
        return jdbc.sql("""
                INSERT INTO notifications (event_id, transaction_id, from_account, to_account, amount, message, from_owner, to_owner)
                VALUES (:eventId, :tx, :from, :to, :amount, :message, :fromOwner, :toOwner)
                ON CONFLICT (event_id) DO NOTHING
                RETURNING """ + " " + COLUMNS)
                .param("eventId", eventId).param("tx", e.transactionId())
                .param("from", e.fromAccountId()).param("to", e.toAccountId())
                .param("amount", e.amount()).param("message", message)
                .param("fromOwner", e.fromOwner()).param("toOwner", e.toOwner())
                .query(NotificationRepository::map)
                .optional();
    }

    /** The newest notifications that concern one user, newest first. */
    List<Notification> latestFor(String subject, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notifications WHERE from_owner = :sub OR to_owner = :sub ORDER BY id DESC LIMIT :limit")
                .param("sub", subject).param("limit", limit)
                .query(NotificationRepository::map).list();
    }

    /** Notifications for one user stored after the given id, oldest first: what a reconnecting client missed. */
    List<Notification> after(String subject, long afterId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notifications WHERE (from_owner = :sub OR to_owner = :sub) AND id > :after ORDER BY id ASC LIMIT :limit")
                .param("sub", subject).param("after", afterId).param("limit", limit)
                .query(NotificationRepository::map).list();
    }

    long countForTransaction(UUID transactionId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE transaction_id = :tx")
                .param("tx", transactionId).query(Long.class).single();
    }

    private static Notification map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Notification(rs.getLong("id"), rs.getObject("transaction_id", UUID.class),
                rs.getObject("from_account", UUID.class), rs.getObject("to_account", UUID.class),
                rs.getBigDecimal("amount"), rs.getString("message"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getString("from_owner"), rs.getString("to_owner"));
    }
}
