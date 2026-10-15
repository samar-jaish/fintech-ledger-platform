package com.portfolio.trading;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * totalValue is cash plus market value of positions that could be priced. complete is false if any could not.
 * Cash comes from the ledger service and positions from this service, so they are read separately: while a trade is
 * PENDING the two can briefly disagree, which pendingTrades makes visible.
 */
public record Portfolio(UUID accountId, String currency, BigDecimal cash, List<PortfolioPosition> positions,
                        BigDecimal totalMarketValue, BigDecimal totalUnrealizedPnl, BigDecimal totalValue,
                        boolean complete, long pendingTrades) { }
