package com.portfolio.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record Account(UUID id, String ownerName, String currency, BigDecimal balance, boolean allowNegative) { }
