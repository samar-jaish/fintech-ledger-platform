-- Transactional outbox: events are written in the same transaction as the change they describe,
-- then a relay publishes them to Kafka. published_at stays NULL until Kafka has acknowledged the event.
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    published_at   TIMESTAMPTZ
);

-- The relay only ever looks at unpublished rows, oldest first.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;

-- Consumer side: remembers which events were handled, so a redelivered event is ignored (idempotent consumer).
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    consumer     VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
