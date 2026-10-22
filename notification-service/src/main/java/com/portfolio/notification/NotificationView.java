package com.portfolio.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The JSON a client receives, in the list and in the live stream. It carries no owner ids: those stay inside this service. */
record NotificationView(long id, UUID transactionId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                        String message, Instant createdAt, String direction) { }
