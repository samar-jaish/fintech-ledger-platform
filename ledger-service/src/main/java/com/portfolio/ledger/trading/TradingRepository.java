package com.portfolio.ledger.trading;

import com.portfolio.ledger.domain.Account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradingRepository {

    boolean accountExists(UUID accountId);

    /** The house account that is the counterparty of every trade, created on first use. Call inside a transaction. */
    Account findOrCreateExchangeAccount(String currency);

    Optional<Trade> findTradeByKey(String idempotencyKey);

    /** Returns false if the idempotency key already exists. */
    boolean insertTrade(Trade trade);

    /** Adds shares and recomputes the weighted average cost, in one atomic statement. */
    void addToHolding(UUID accountId, String symbol, BigDecimal quantity, BigDecimal price);

    /** Removes shares only if enough are held. Returns false when there are not. */
    boolean removeFromHolding(UUID accountId, String symbol, BigDecimal quantity);

    /** Positions with a quantity above zero, ordered by symbol. */
    List<Holding> findHoldings(UUID accountId);

    /** Newest first. */
    List<Trade> findTrades(UUID accountId, int limit);
}
