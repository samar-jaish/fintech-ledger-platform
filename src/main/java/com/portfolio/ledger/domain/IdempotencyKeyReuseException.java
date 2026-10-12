package com.portfolio.ledger.domain;

public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String key) {
        super("Idempotency key was already used with a different request: " + key);
    }
}
