package com.portfolio.ledger.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPriceHistoryRepository implements PriceHistoryRepository {

    private static final RowMapper<PricePoint> MAPPER = (rs, i) -> new PricePoint(
            rs.getString("symbol"),
            rs.getObject("price_date", LocalDate.class),
            rs.getBigDecimal("close_price"),
            rs.getString("source"));

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcPriceHistoryRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int saveAll(List<PricePoint> points) {
        if (points.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(points.size());
        for (PricePoint p : points) {
            batch.add(new Object[]{p.symbol(), p.date(), p.close(), p.source()});
        }
        int[] counts = jdbcTemplate.batchUpdate("""
                INSERT INTO price_history (symbol, price_date, close_price, source)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (symbol, price_date) DO NOTHING
                """, batch);
        int inserted = 0;
        for (int c : counts) {
            if (c > 0) {
                inserted += c;
            }
        }
        return inserted;
    }

    @Override
    public Optional<PricePoint> findLatest(String symbol) {
        return jdbc.sql("""
                SELECT symbol, price_date, close_price, source FROM price_history
                WHERE symbol = :symbol ORDER BY price_date DESC LIMIT 1
                """).param("symbol", symbol).query(MAPPER).optional();
    }

    @Override
    public List<PricePoint> findRange(String symbol, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT symbol, price_date, close_price, source FROM price_history
                WHERE symbol = :symbol AND price_date BETWEEN :from AND :to
                ORDER BY price_date
                """).param("symbol", symbol).param("from", from).param("to", to).query(MAPPER).list();
    }
}
