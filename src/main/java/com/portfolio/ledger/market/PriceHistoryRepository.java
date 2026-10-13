package com.portfolio.ledger.market;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PriceHistoryRepository {

    /** Stores points, ignoring ones that already exist. Returns how many were newly inserted. */
    int saveAll(List<PricePoint> points);

    Optional<PricePoint> findLatest(String symbol);

    /** Oldest first, inclusive range. */
    List<PricePoint> findRange(String symbol, LocalDate from, LocalDate to);
}
