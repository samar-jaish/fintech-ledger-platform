-- Version counter for optimistic locking. Every balance change increments it.
ALTER TABLE accounts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
