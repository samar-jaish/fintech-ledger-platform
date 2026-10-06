-- Convention: CREDIT increases an account balance, DEBIT decreases it
-- (customer-account / liability view). Every transaction must have
-- total DEBITs == total CREDITs.

CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    owner_name     VARCHAR(100)  NOT NULL,
    currency       CHAR(3)       NOT NULL,
    balance        NUMERIC(19,4) NOT NULL DEFAULT 0,
    allow_negative BOOLEAN       NOT NULL DEFAULT FALSE,  -- true only for system/house accounts
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0 OR allow_negative)
);

CREATE TABLE ledger_transactions (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES ledger_transactions(id),
    account_id     UUID          NOT NULL REFERENCES accounts(id),
    direction      VARCHAR(6)    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Statement queries: "entries for account X, newest first"
CREATE INDEX idx_entries_account_ts ON ledger_entries (account_id, created_at DESC);
CREATE INDEX idx_entries_tx ON ledger_entries (transaction_id);

-- Ledger is append-only: corrections are new compensating entries, never edits.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
