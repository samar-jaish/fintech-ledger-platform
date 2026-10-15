package com.portfolio.ledger.batch;

/** The day is already closed (and a rerun was not requested), or a close for that day is running right now. */
public class NightlyCloseConflictException extends RuntimeException {
    public NightlyCloseConflictException(String message) {
        super(message);
    }
}
