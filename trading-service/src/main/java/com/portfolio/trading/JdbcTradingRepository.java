package com.portfolio.trading;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTradingRepository implements TradingRepository {

    private static final String TRADE_COLS = "id, idempotency_key, request_hash, account_id, exchange_account_id, symbol, "
            + "side, quantity, price, notional, currency, price_source, price_as_of, created_at, status, reject_reason";

    private static final RowMapper<Trade> TRADE_MAPPER = (rs, i) -> new Trade(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getObject("account_id", UUID.class),
            rs.getObject("exchange_account_id", UUID.class),
            rs.getString("symbol"),
            Side.valueOf(rs.getString("side")),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("notional"),
            rs.getString("currency"),
            rs.getString("price_source"),
            rs.getObject("price_as_of", OffsetDateTime.class).toInstant(),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            TradeStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason"));

    private static final RowMapper<Holding> HOLDING_MAPPER = (rs, i) -> new Holding(
            rs.getObject("account_id", UUID.class),
            rs.getString("symbol"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("avg_cost"));

    private final JdbcClient jdbc;

    public JdbcTradingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Trade> findTradeByKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + TRADE_COLS + " FROM trades WHERE idempotency_key = :key")
                .param("key", idempotencyKey).query(TRADE_MAPPER).optional();
    }

    @Override
    public Optional<Trade> findTradeById(UUID id) {
        return jdbc.sql("SELECT " + TRADE_COLS + " FROM trades WHERE id = :id")
                .param("id", id).query(TRADE_MAPPER).optional();
    }

    @Override
    public boolean insertPending(Trade t) {
        int rows = jdbc.sql("INSERT INTO trades (" + TRADE_COLS + ") VALUES "
                + "(:id, :key, :hash, :account, :exchange, :symbol, :side, :qty, :price, :notional, :currency, "
                + ":source, :asOf, :createdAt, 'PENDING', NULL) ON CONFLICT (idempotency_key) DO NOTHING")
                .param("id", t.id()).param("key", t.idempotencyKey()).param("hash", t.requestHash())
                .param("account", t.accountId()).param("exchange", t.exchangeAccountId())
                .param("symbol", t.symbol()).param("side", t.side().name())
                .param("qty", t.quantity()).param("price", t.price()).param("notional", t.notional())
                .param("currency", t.currency()).param("source", t.priceSource())
                .param("asOf", utc(t.priceAsOf())).param("createdAt", utc(t.createdAt()))
                .update();
        return rows == 1;
    }

    @Override
    public boolean markCompleted(UUID tradeId, Instant at) {
        return jdbc.sql("UPDATE trades SET status = 'COMPLETED', completed_at = :at WHERE id = :id AND status = 'PENDING'")
                .param("at", utc(at)).param("id", tradeId).update() == 1;
    }

    @Override
    public boolean markRejected(UUID tradeId, String reason, Instant at) {
        return jdbc.sql("UPDATE trades SET status = 'REJECTED', reject_reason = :reason, completed_at = :at "
                + "WHERE id = :id AND status = 'PENDING'")
                .param("reason", reason).param("at", utc(at)).param("id", tradeId).update() == 1;
    }

    @Override
    public void addToHolding(UUID accountId, String symbol, BigDecimal quantity, BigDecimal price) {
        // Right-hand sides in DO UPDATE see the old row, so the weighted average uses the old quantity.
        jdbc.sql("""
                INSERT INTO holdings (account_id, symbol, quantity, avg_cost)
                VALUES (:account, :symbol, :qty, :price)
                ON CONFLICT (account_id, symbol) DO UPDATE SET
                    avg_cost = CASE WHEN holdings.quantity + EXCLUDED.quantity = 0 THEN EXCLUDED.avg_cost
                               ELSE ROUND((holdings.quantity * holdings.avg_cost + EXCLUDED.quantity * EXCLUDED.avg_cost)
                                          / (holdings.quantity + EXCLUDED.quantity), 4) END,
                    quantity = holdings.quantity + EXCLUDED.quantity,
                    updated_at = now()
                """)
                .param("account", accountId).param("symbol", symbol).param("qty", quantity).param("price", price)
                .update();
    }

    @Override
    public boolean removeFromHolding(UUID accountId, String symbol, BigDecimal quantity) {
        return jdbc.sql("""
                UPDATE holdings SET quantity = quantity - :qty, updated_at = now()
                WHERE account_id = :account AND symbol = :symbol AND quantity >= :qty
                """)
                .param("qty", quantity).param("account", accountId).param("symbol", symbol).update() == 1;
    }

    @Override
    public void restoreHolding(UUID accountId, String symbol, BigDecimal quantity) {
        jdbc.sql("UPDATE holdings SET quantity = quantity + :qty, updated_at = now() "
                + "WHERE account_id = :account AND symbol = :symbol")
                .param("qty", quantity).param("account", accountId).param("symbol", symbol).update();
    }

    @Override
    public List<Holding> findHoldings(UUID accountId) {
        return jdbc.sql("""
                SELECT account_id, symbol, quantity, avg_cost FROM holdings
                WHERE account_id = :account AND quantity > 0 ORDER BY symbol
                """).param("account", accountId).query(HOLDING_MAPPER).list();
    }

    @Override
    public List<Trade> findTrades(UUID accountId, int limit) {
        return jdbc.sql("SELECT " + TRADE_COLS + " FROM trades WHERE account_id = :account "
                + "ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("account", accountId).param("limit", limit).query(TRADE_MAPPER).list();
    }

    @Override
    public List<Trade> findPendingBefore(Instant cutoff, int limit) {
        return jdbc.sql("SELECT " + TRADE_COLS + " FROM trades WHERE status = 'PENDING' AND created_at < :cutoff "
                + "ORDER BY created_at LIMIT :limit")
                .param("cutoff", utc(cutoff)).param("limit", limit).query(TRADE_MAPPER).list();
    }

    @Override
    public long countPending(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM trades WHERE account_id = :account AND status = 'PENDING'")
                .param("account", accountId).query(Long.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
