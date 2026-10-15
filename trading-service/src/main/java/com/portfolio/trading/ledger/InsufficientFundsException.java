package com.portfolio.trading.ledger;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId) {
        super("Insufficient funds in account " + accountId);
    }
}
