package com.portfolio.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradingRepository {

    Optional<Trade> findTradeByKey(String idempotencyKey);

    Optional<Trade> findTradeById(UUID id);

    /** Stores a new PENDING trade. Returns false if the idempotency key already exists. */
    boolean insertPending(Trade trade);

    /** PENDING to COMPLETED. Returns false if the trade was not pending (someone else already finished it). */
    boolean markCompleted(UUID tradeId, Instant at);

    /** PENDING to REJECTED. Returns false if the trade was not pending. */
    boolean markRejected(UUID tradeId, String reason, Instant at);

    /** Adds shares and recomputes the weighted average cost, in one atomic statement. */
    void addToHolding(UUID accountId, String symbol, BigDecimal quantity, BigDecimal price);

    /** Removes shares only if enough are held. Returns false when there are not. */
    boolean removeFromHolding(UUID accountId, String symbol, BigDecimal quantity);

    /** Gives back shares removed for a sell that was rejected. The average cost is unchanged by a removal. */
    void restoreHolding(UUID accountId, String symbol, BigDecimal quantity);

    /** Positions with a quantity above zero, ordered by symbol. */
    List<Holding> findHoldings(UUID accountId);

    /** Newest first. */
    List<Trade> findTrades(UUID accountId, int limit);

    /** Pending trades created before the cutoff, oldest first. */
    List<Trade> findPendingBefore(Instant cutoff, int limit);

    long countPending(UUID accountId);
}
