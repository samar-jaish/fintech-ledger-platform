package com.portfolio.trading;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One holding valued at the latest price. If no price could be obtained, price, marketValue,
 * unrealizedPnl, priceSource and priceAsOf are null and priceAvailable is false.
 */
public record PortfolioPosition(String symbol, BigDecimal quantity, BigDecimal avgCost, BigDecimal costBasis,
                                BigDecimal price, BigDecimal marketValue, BigDecimal unrealizedPnl,
                                String priceSource, Instant priceAsOf, boolean priceAvailable) { }
