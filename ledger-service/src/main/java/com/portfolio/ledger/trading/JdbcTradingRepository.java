package com.portfolio.ledger.trading;

import com.portfolio.ledger.domain.Account;
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

    static final String EXCHANGE_NAME = "EXCHANGE";
    /** Serializes creation of the exchange account so concurrent first trades do not create two. */
    private static final long EXCHANGE_LOCK_KEY = 7_001L;

    private static final String TRADE_COLS = "id, idempotency_key, request_hash, account_id, symbol, side, quantity, "
            + "price, notional, currency, price_source, price_as_of, executed_at";

    private static final RowMapper<Trade> TRADE_MAPPER = (rs, i) -> new Trade(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getObject("account_id", UUID.class),
            rs.getString("symbol"),
            Side.valueOf(rs.getString("side")),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("notional"),
            rs.getString("currency"),
            rs.getString("price_source"),
            rs.getObject("price_as_of", OffsetDateTime.class).toInstant(),
            rs.getObject("executed_at", OffsetDateTime.class).toInstant());

    private static final RowMapper<Holding> HOLDING_MAPPER = (rs, i) -> new Holding(
            rs.getObject("account_id", UUID.class),
            rs.getString("symbol"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("avg_cost"));

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, i) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("owner_name"),
            rs.getString("currency"),
            rs.getBigDecimal("balance"),
            rs.getBoolean("allow_negative"));

    private final JdbcClient jdbc;

    public JdbcTradingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean accountExists(UUID accountId) {
        return jdbc.sql("SELECT 1 FROM accounts WHERE id = :id").param("id", accountId)
                .query(Integer.class).optional().isPresent();
    }

    @Override
    public Account findOrCreateExchangeAccount(String currency) {
        // Transaction-scoped advisory lock: released automatically at commit or rollback.
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS locked")
                .param("key", EXCHANGE_LOCK_KEY).query(Integer.class).single();
        Optional<Account> existing = jdbc.sql("""
                SELECT id, owner_name, currency, balance, allow_negative FROM accounts
                WHERE owner_name = :name AND currency = :currency AND allow_negative
                ORDER BY created_at, id LIMIT 1
                """).param("name", EXCHANGE_NAME).param("currency", currency).query(ACCOUNT_MAPPER).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, owner_name, currency, allow_negative) VALUES (:id, :name, :currency, TRUE)")
                .param("id", id).param("name", EXCHANGE_NAME).param("currency", currency).update();
        return jdbc.sql("SELECT id, owner_name, currency, balance, allow_negative FROM accounts WHERE id = :id")
                .param("id", id).query(ACCOUNT_MAPPER).single();
    }

    @Override
    public Optional<Trade> findTradeByKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + TRADE_COLS + " FROM trades WHERE idempotency_key = :key")
                .param("key", idempotencyKey).query(TRADE_MAPPER).optional();
    }

    @Override
    public boolean insertTrade(Trade t) {
        int rows = jdbc.sql("INSERT INTO trades (" + TRADE_COLS + ") VALUES "
                + "(:id, :key, :hash, :account, :symbol, :side, :qty, :price, :notional, :currency, :source, :asOf, :executedAt) "
                + "ON CONFLICT (idempotency_key) DO NOTHING")
                .param("id", t.id()).param("key", t.idempotencyKey()).param("hash", t.requestHash())
                .param("account", t.accountId()).param("symbol", t.symbol()).param("side", t.side().name())
                .param("qty", t.quantity()).param("price", t.price()).param("notional", t.notional())
                .param("currency", t.currency()).param("source", t.priceSource())
                .param("asOf", utc(t.priceAsOf())).param("executedAt", utc(t.executedAt()))
                .update();
        return rows == 1;
    }

    @Override
    public void addToHolding(UUID accountId, String symbol, BigDecimal quantity, BigDecimal price) {
        // Right-hand sides in DO UPDATE see the old row, so the weighted average uses the old quantity.
        jdbc.sql("""
                INSERT INTO holdings (account_id, symbol, quantity, avg_cost)
                VALUES (:account, :symbol, :qty, :price)
                ON CONFLICT (account_id, symbol) DO UPDATE SET
                    avg_cost = ROUND((holdings.quantity * holdings.avg_cost + EXCLUDED.quantity * EXCLUDED.avg_cost)
                                     / (holdings.quantity + EXCLUDED.quantity), 4),
                    quantity = holdings.quantity + EXCLUDED.quantity,
                    updated_at = now()
                """)
                .param("account", accountId).param("symbol", symbol).param("qty", quantity).param("price", price)
                .update();
    }

    @Override
    public boolean removeFromHolding(UUID accountId, String symbol, BigDecimal quantity) {
        int rows = jdbc.sql("""
                UPDATE holdings SET quantity = quantity - :qty, updated_at = now()
                WHERE account_id = :account AND symbol = :symbol AND quantity >= :qty
                """)
                .param("qty", quantity).param("account", accountId).param("symbol", symbol).update();
        return rows == 1;
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
                + "ORDER BY executed_at DESC, id DESC LIMIT :limit")
                .param("account", accountId).param("limit", limit).query(TRADE_MAPPER).list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
