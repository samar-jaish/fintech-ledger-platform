# ADR 0010: Notification service as a separate module with its own database

Status: Accepted

## Context

Transfer events (ADR 0009) need a consumer. Keeping it inside the ledger application would not show the point of
events: the producer must not know or care who listens, and a slow or broken listener must not affect transfers.

## Decision

- A Maven multi-module build: a parent pom with `ledger-service` and `notification-service`. Each module is its own
  Spring Boot application, started and deployed independently.
- The notification service owns its own database (`notifications`), created by a Postgres init script. The two
  services share no tables and no code.
- The event format is the contract. The notification service has its own event record and ignores unknown fields,
  so the ledger can add fields without breaking it.
- Idempotent consumer: `notifications.event_id` is unique and the insert uses `ON CONFLICT DO NOTHING`, so a
  redelivered event creates nothing new.
- Failure policy: a message that fails is retried 3 times one second apart, then published to
  `ledger.transfers.DLT` (dead-letter topic) and the consumer moves on. One bad message cannot block the rest.
- The consumer group is `notification-service`, so it has its own read position (offset) on the topic.

## Consequences

- Ledger transfers keep working when the notification service is down. The events wait in Kafka and are processed
  when it returns.
- Nobody reads the dead-letter topic yet. A real system would alert on it and offer a replay tool.
- Running two services means two processes locally and more to start. Phase 4 packages them as containers.
- Sharing no code means the event shape is duplicated. A schema registry or contract tests would guard it in production.
- The ledger's own `processed_events` table from Phase 3a is dropped (migration V9); it only served the in-process
  example consumer.
