package com.portfolio.trading;

/** Where a trade is in the saga. PENDING is the only state that is ever retried. */
public enum TradeStatus { PENDING, COMPLETED, REJECTED }
