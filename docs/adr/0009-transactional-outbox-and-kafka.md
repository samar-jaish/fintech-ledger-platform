# ADR 0009: Transactional outbox and Kafka for transfer events

Status: Accepted

## Context

Other services (notifications, trading, reporting) need to know when a transfer happens. Publishing to Kafka from the
transfer code has a flaw: the database commit and the Kafka send are two separate systems and cannot commit together.
Send first and the database can fail, announcing a transfer that never happened. Commit first and the process can die
before sending, so a real transfer is never announced.

## Decision

- Transactional outbox. The transfer writes a `TransferCompleted` row into `outbox_events` in the same database
  transaction as the ledger entries. It commits with them or not at all.
- A relay (`OutboxRelay`) polls unpublished rows oldest first, sends each to the Kafka topic `ledger.transfers` with
  `acks=all` and an idempotent producer, and marks the row published after Kafka acknowledges it.
- The relay claims rows with `FOR UPDATE SKIP LOCKED`, so several instances can run without sending the same row twice
  at the same time.
- Delivery is at-least-once: a crash between the Kafka acknowledgement and `published_at` causes a resend. Consumers
  must be idempotent. The example consumer records `event-id` in `processed_events` with `ON CONFLICT DO NOTHING`.
- The Kafka message key is the transaction id and the headers carry `event-id` and `event-type`. The JSON payload
  field names are a contract, pinned by a test.
- The relay and the example consumer run only when `ledger.outbox.relay.enabled=true`, so the app still starts without
  a broker. Kafka runs locally in KRaft mode (no ZooKeeper).

## Consequences

- Events appear with a small delay (the poll interval, 500 ms by default).
- Ordering is guaranteed only per Kafka key (per transaction here). Per-account ordering would need the account id as the key.
- The relay keeps a database transaction open while it sends a batch, which is fine for small batches and a reason to
  keep the batch size modest.
- Published rows are never deleted yet; a clean-up job (or partitioning) is needed for volume.
- The alternative to polling is change data capture (for example Debezium reading the Postgres log). It removes the
  polling delay but adds another component to run.
