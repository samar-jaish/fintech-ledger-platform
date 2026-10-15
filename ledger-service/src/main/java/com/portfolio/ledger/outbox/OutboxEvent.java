package com.portfolio.ledger.outbox;

import java.util.UUID;

/** One row of outbox_events as the relay sees it. */
record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload) { }
