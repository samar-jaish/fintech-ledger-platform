-- One row per notification. The unique event_id is the idempotency guard: Kafka delivers at least once,
-- so the same event can arrive twice, and the second insert is ignored.
CREATE TABLE notifications (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID          NOT NULL UNIQUE,
    transaction_id UUID          NOT NULL,
    from_account   UUID          NOT NULL,
    to_account     UUID          NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    message        TEXT          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
