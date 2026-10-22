package com.portfolio.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What this service needs from a transfer event. It is this service's own copy of the contract, not a class shared
 * with the ledger: services share the message format, not code. Unknown fields are ignored so the ledger can add
 * fields without breaking us (a "tolerant reader").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TransferCompletedEvent(UUID transactionId, UUID fromAccountId, UUID toAccountId,
                              BigDecimal amount, String description, String fromOwner, String toOwner) { }
