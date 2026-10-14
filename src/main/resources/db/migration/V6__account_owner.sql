-- The identity provider's user id ("sub" claim) that owns the account. NULL for house/system accounts.
ALTER TABLE accounts ADD COLUMN owner_subject VARCHAR(100);
CREATE INDEX idx_accounts_owner_subject ON accounts (owner_subject) WHERE owner_subject IS NOT NULL;
