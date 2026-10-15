package com.portfolio.ledger.domain;

import java.util.UUID;

/** Accounts created by migrations. */
public final class SystemAccounts {

    /** Counterparty of API deposits (USD). Created in V5__seed_house_account.sql. */
    public static final UUID HOUSE_USD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemAccounts() { }
}
