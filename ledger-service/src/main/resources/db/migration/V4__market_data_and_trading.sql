-- Daily closing prices, recorded from whichever market data provider was active.
CREATE TABLE price_history (
    symbol      VARCHAR(12)   NOT NULL,
    price_date  DATE          NOT NULL,
    close_price NUMERIC(19,4) NOT NULL CHECK (close_price > 0),
    source      VARCHAR(30)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, price_date)
);

-- Current positions. One row per (account, symbol).
CREATE TABLE holdings (
    account_id UUID          NOT NULL REFERENCES accounts(id),
    symbol     VARCHAR(12)   NOT NULL,
    quantity   NUMERIC(19,6) NOT NULL CHECK (quantity >= 0),
    avg_cost   NUMERIC(19,4) NOT NULL CHECK (avg_cost >= 0),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, symbol)
);

-- Executed trades. The price and its source are stored with every trade, so simulated
-- prices can never be mistaken for real ones later.
CREATE TABLE trades (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(100)  NOT NULL UNIQUE,
    request_hash    CHAR(64)      NOT NULL,
    account_id      UUID          NOT NULL REFERENCES accounts(id),
    symbol          VARCHAR(12)   NOT NULL,
    side            VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity        NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    price           NUMERIC(19,4) NOT NULL CHECK (price > 0),
    notional        NUMERIC(19,4) NOT NULL CHECK (notional > 0),
    currency        CHAR(3)       NOT NULL,
    price_source    VARCHAR(30)   NOT NULL,
    price_as_of     TIMESTAMPTZ   NOT NULL,
    executed_at     TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_trades_account_ts ON trades (account_id, executed_at DESC);
