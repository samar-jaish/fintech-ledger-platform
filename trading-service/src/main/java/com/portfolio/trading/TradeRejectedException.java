package com.portfolio.trading;

/** The trade was refused and is final (status REJECTED). code is one of INSUFFICIENT_FUNDS, INSUFFICIENT_HOLDINGS, LEDGER_REJECTED. */
public class TradeRejectedException extends RuntimeException {

    private final String code;

    public TradeRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
