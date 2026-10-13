package com.portfolio.ledger.trading;

import java.util.UUID;

public class InsufficientHoldingsException extends RuntimeException {
    public InsufficientHoldingsException(UUID accountId, String symbol) {
        super("Account " + accountId + " does not hold enough " + symbol);
    }
}
