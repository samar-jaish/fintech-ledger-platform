-- Trading owns its data: price history, positions and trades. Cash lives in the ledger service, not here.

CREATE TABLE price_history (
    symbol      VARCHAR(12)   NOT NULL,
    price_date  DATE          NOT NULL,
    close_price NUMERIC(19,4) NOT NULL CHECK (close_price > 0),
    source      VARCHAR(30)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, price_date)
);

-- Current positions. One row per (account, symbol). account_id is an id in the ledger service, so no foreign key.
CREATE TABLE holdings (
    account_id UUID          NOT NULL,
    symbol     VARCHAR(12)   NOT NULL,
    quantity   NUMERIC(19,6) NOT NULL CHECK (quantity >= 0),
    avg_cost   NUMERIC(19,4) NOT NULL CHECK (avg_cost >= 0),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, symbol)
);

-- A trade is the saga's state. It starts PENDING and ends COMPLETED or REJECTED.
--   PENDING   : the cash movement in the ledger has not been confirmed yet (it is retried until it is)
--   COMPLETED : the ledger confirmed the cash movement and the position is final
--   REJECTED  : the ledger refused (or the position was too small); any reserved shares were returned
-- The price and its source are stored with every trade, so simulated prices can never be mistaken for real ones.
CREATE TABLE trades (
    id                  UUID PRIMARY KEY,
    idempotency_key     VARCHAR(100)  NOT NULL UNIQUE,
    request_hash        CHAR(64)      NOT NULL,
    account_id          UUID          NOT NULL,
    exchange_account_id UUID          NOT NULL,
    symbol              VARCHAR(12)   NOT NULL,
    side                VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity            NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    price               NUMERIC(19,4) NOT NULL CHECK (price > 0),
    notional            NUMERIC(19,4) NOT NULL CHECK (notional > 0),
    currency            CHAR(3)       NOT NULL,
    price_source        VARCHAR(30)   NOT NULL,
    price_as_of         TIMESTAMPTZ   NOT NULL,
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED')),
    reject_reason       VARCHAR(40),
    created_at          TIMESTAMPTZ   NOT NULL,
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_trades_account_ts ON trades (account_id, created_at DESC);
-- The recovery job only looks at trades that are still pending.
CREATE INDEX idx_trades_pending ON trades (created_at) WHERE status = 'PENDING';
