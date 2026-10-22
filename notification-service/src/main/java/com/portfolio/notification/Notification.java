package com.portfolio.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One stored notification, as read from the database. */
record Notification(long id, UUID transactionId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                    String message, Instant createdAt, String fromOwner, String toOwner) {

    /** What one particular user is shown. Direction is relative to that user: SENT, RECEIVED or SELF (both accounts are theirs). */
    NotificationView viewFor(String subject) {
        boolean sent = subject.equals(fromOwner);
        boolean received = subject.equals(toOwner);
        String direction = sent && received ? "SELF" : sent ? "SENT" : "RECEIVED";
        return new NotificationView(id, transactionId, fromAccountId, toAccountId, amount, message, createdAt, direction);
    }
}
