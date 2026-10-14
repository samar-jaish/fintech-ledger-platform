-- End-of-day snapshot written by the nightly close job (one row per account per business date, UTC days).
--   closing_balance : balance at the end of business_date, rebuilt from ledger entries
--   cached_balance  : accounts.balance when the job read it
--   entry_count     : ledger entries posted on business_date
--   reconciled      : cached balance equals the sum of all entries (same snapshot)
CREATE TABLE daily_balances (
    account_id      UUID          NOT NULL REFERENCES accounts(id),
    business_date   DATE          NOT NULL,
    closing_balance NUMERIC(19,4) NOT NULL,
    cached_balance  NUMERIC(19,4) NOT NULL,
    entry_count     BIGINT        NOT NULL,
    reconciled      BOOLEAN       NOT NULL,
    computed_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, business_date)
);

CREATE INDEX idx_daily_balances_date_unreconciled ON daily_balances (business_date) WHERE NOT reconciled;
