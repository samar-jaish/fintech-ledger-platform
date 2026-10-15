package com.portfolio.trading.ledger;

/** The ledger could not give a usable answer. Whether the operation happened is unknown. */
public class LedgerUnavailableException extends RuntimeException {
    public LedgerUnavailableException(String message) {
        super(message);
    }

    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
