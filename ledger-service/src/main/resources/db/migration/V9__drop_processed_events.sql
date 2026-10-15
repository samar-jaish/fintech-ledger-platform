-- The consumer of transfer events moved into notification-service, which keeps its own idempotency record
-- in its own database. This table was only used by the in-process example consumer from Phase 3a.
DROP TABLE IF EXISTS processed_events;
