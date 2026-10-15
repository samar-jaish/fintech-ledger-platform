-- Well-known house account used as the counterparty for deposits through the API (USD only).
-- It may go negative: it represents money entering the system. There are no real payment rails.
INSERT INTO accounts (id, owner_name, currency, allow_negative)
VALUES ('00000000-0000-0000-0000-000000000001', 'HOUSE-USD', 'USD', TRUE)
ON CONFLICT (id) DO NOTHING;
