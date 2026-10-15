package com.portfolio.trading.ledger;

/** A bearer token that identifies this service itself (not a user) when it calls other services. */
public interface ServiceTokens {
    String token();
}
