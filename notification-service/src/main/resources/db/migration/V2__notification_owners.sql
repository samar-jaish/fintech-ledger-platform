-- Who each notification concerns: the identity provider user ids (JWT "sub") of the two accounts' owners, copied from the
-- event so this service never has to call the ledger. Null for a house account, and for notifications stored before
-- this column existed (those are not shown to anyone).
ALTER TABLE notifications ADD COLUMN from_owner VARCHAR(100);
ALTER TABLE notifications ADD COLUMN to_owner   VARCHAR(100);
CREATE INDEX idx_notifications_from_owner ON notifications (from_owner, id) WHERE from_owner IS NOT NULL;
CREATE INDEX idx_notifications_to_owner   ON notifications (to_owner, id)   WHERE to_owner IS NOT NULL;
