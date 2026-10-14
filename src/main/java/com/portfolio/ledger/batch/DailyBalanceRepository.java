package com.portfolio.ledger.batch;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to the snapshots written by the nightly close. */
@Repository
public class DailyBalanceRepository {

    private static final RowMapper<DailyBalance> MAPPER = (rs, i) -> new DailyBalance(
            rs.getObject("account_id", UUID.class),
            rs.getObject("business_date", LocalDate.class),
            rs.getBigDecimal("closing_balance"),
            rs.getBigDecimal("cached_balance"),
            rs.getLong("entry_count"),
            rs.getBoolean("reconciled"));

    private final JdbcClient jdbc;

    public DailyBalanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest business date first. */
    public List<DailyBalance> findRecent(UUID accountId, int limit) {
        return jdbc.sql("SELECT * FROM daily_balances WHERE account_id = :id ORDER BY business_date DESC LIMIT :limit")
                .param("id", accountId).param("limit", limit).query(MAPPER).list();
    }

    public Optional<DailyBalance> find(UUID accountId, LocalDate businessDate) {
        return jdbc.sql("SELECT * FROM daily_balances WHERE account_id = :id AND business_date = :d")
                .param("id", accountId).param("d", businessDate).query(MAPPER).optional();
    }

    public long countUnreconciled(LocalDate businessDate) {
        return jdbc.sql("SELECT count(*) FROM daily_balances WHERE business_date = :d AND NOT reconciled")
                .param("d", businessDate).query(Long.class).single();
    }
}
