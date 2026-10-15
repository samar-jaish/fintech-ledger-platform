package com.portfolio.ledger.trading;

import java.math.BigDecimal;
import java.util.UUID;

public record Holding(UUID accountId, String symbol, BigDecimal quantity, BigDecimal avgCost) { }
