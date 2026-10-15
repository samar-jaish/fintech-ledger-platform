package com.portfolio.trading.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** The part of a ledger account that trading cares about. */
public record AccountView(UUID id, String ownerName, String currency, BigDecimal balance) { }
