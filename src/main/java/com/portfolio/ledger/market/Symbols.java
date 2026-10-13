package com.portfolio.ledger.market;

import java.util.regex.Pattern;

public final class Symbols {
    private static final Pattern VALID = Pattern.compile("[A-Z][A-Z0-9.\\-]{0,9}");

    private Symbols() { }

    /** Upper-cases and validates a ticker symbol. Throws IllegalArgumentException if it is malformed. */
    public static String normalize(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        String s = symbol.trim().toUpperCase();
        if (!VALID.matcher(s).matches()) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }
        return s;
    }
}
