-- Trading (trades, holdings, price history) moved into trading-service, which owns its own database.
-- These tables are no longer read or written by the ledger.
DROP TABLE IF EXISTS trades;
DROP TABLE IF EXISTS holdings;
DROP TABLE IF EXISTS price_history;
