package com.portfolio.ledger.trading;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** totalValue is cash plus market value of positions that could be priced. complete is false if any could not. */
public record Portfolio(UUID accountId, String currency, BigDecimal cash, List<PortfolioPosition> positions,
                        BigDecimal totalMarketValue, BigDecimal totalUnrealizedPnl, BigDecimal totalValue,
                        boolean complete) { }
