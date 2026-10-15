package com.portfolio.trading.ledger;

/** The ledger understood the request and refused it for a reason other than funds or a missing account. */
public class LedgerRejectedException extends RuntimeException {
    public LedgerRejectedException(String message) {
        super(message);
    }
}
