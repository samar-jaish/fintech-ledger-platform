package com.portfolio.trading;

/** The same idempotency key arrived with a different request. That is a client bug, not a retry. */
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String key) {
        super("Idempotency key '" + key + "' was already used with a different request");
    }
}
