-- Fingerprint (SHA-256 hex) of the request that first used an idempotency key.
-- A repeat of the key with a different payload is rejected instead of silently replayed.
ALTER TABLE ledger_transactions ADD COLUMN request_hash CHAR(64);
