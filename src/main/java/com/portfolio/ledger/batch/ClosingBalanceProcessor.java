package com.portfolio.ledger.batch;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Computes one account's end-of-day figures from the ledger entries.
 *
 * Everything is read in ONE statement, so cached balance and entries come from the same snapshot. A transfer
 * commits its entries and its balance update together, so a statement sees both or neither. Reading them in
 * separate statements would let a transfer land in between and raise a false mismatch.
 */
class ClosingBalanceProcessor implements ItemProcessor<UUID, DailyBalance> {

    private static final String SQL = """
            SELECT a.balance AS cached,
                   COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS total,
                   COALESCE(SUM(CASE WHEN e.created_at < :cutoff
                                     THEN (CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                                     ELSE 0 END), 0) AS closing,
                   COUNT(e.id) FILTER (WHERE e.created_at >= :dayStart AND e.created_at < :cutoff) AS day_entries
            FROM accounts a
            LEFT JOIN ledger_entries e ON e.account_id = a.id
            WHERE a.id = :id
            GROUP BY a.id, a.balance
            """;

    private final JdbcClient jdbc;
    private final LocalDate businessDate;
    private final OffsetDateTime dayStart;
    private final OffsetDateTime cutoff;

    ClosingBalanceProcessor(JdbcClient jdbc, LocalDate businessDate) {
        this.jdbc = jdbc;
        this.businessDate = businessDate;
        this.dayStart = businessDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        this.cutoff = businessDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    @Override
    public DailyBalance process(UUID accountId) {
        return jdbc.sql(SQL)
                .param("id", accountId).param("dayStart", dayStart).param("cutoff", cutoff)
                .query((rs, i) -> {
                    var cached = rs.getBigDecimal("cached");
                    var total = rs.getBigDecimal("total");
                    return new DailyBalance(accountId, businessDate, rs.getBigDecimal("closing"), cached,
                            rs.getLong("day_entries"), cached.compareTo(total) == 0);
                })
                .single();
    }
}
