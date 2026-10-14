package com.portfolio.ledger.repository;

import com.portfolio.ledger.domain.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private static final String ACCOUNT_COLS = "id, owner_name, currency, balance, allow_negative";

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, i) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("owner_name"),
            rs.getString("currency"),
            rs.getBigDecimal("balance"),
            rs.getBoolean("allow_negative"));

    private static final RowMapper<VersionedAccount> VERSIONED_MAPPER = (rs, i) ->
            new VersionedAccount(ACCOUNT_MAPPER.mapRow(rs, i), rs.getLong("version"));

    private static final RowMapper<LedgerEntry> ENTRY_MAPPER = (rs, i) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getObject("transaction_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            Direction.valueOf(rs.getString("direction")),
            rs.getBigDecimal("amount"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private static final RowMapper<BalanceMismatch> MISMATCH_MAPPER = (rs, i) -> new BalanceMismatch(
            rs.getObject("id", UUID.class),
            rs.getBigDecimal("cached"),
            rs.getBigDecimal("computed"));

    private static final String MISMATCH_SQL = """
            SELECT a.id, a.balance AS cached,
                   COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS computed
            FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
            %s
            GROUP BY a.id, a.balance
            HAVING a.balance <> COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            """;

    private final JdbcClient jdbc;

    public JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Account createAccount(String ownerName, String currency, boolean allowNegative, String ownerSubject) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, owner_name, currency, allow_negative, owner_subject) "
                        + "VALUES (:id, :owner, :cur, :neg, :sub)")
                .param("id", id).param("owner", ownerName).param("cur", currency).param("neg", allowNegative)
                .param("sub", ownerSubject)
                .update();
        return findAccount(id).orElseThrow();
    }

    @Override
    public boolean isOwnedBy(UUID accountId, String ownerSubject) {
        if (ownerSubject == null) {
            return false;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts WHERE id = :id AND owner_subject = :sub)")
                .param("id", accountId).param("sub", ownerSubject)
                .query(Boolean.class).single();
    }

    @Override
    public Optional<Account> findAccount(UUID id) {
        return jdbc.sql("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE id = :id")
                .param("id", id).query(ACCOUNT_MAPPER).optional();
    }

    @Override
    public List<Account> lockAccounts(Collection<UUID> ids) {
        return jdbc.sql("SELECT " + ACCOUNT_COLS + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids).query(ACCOUNT_MAPPER).list();
    }

    @Override
    public List<VersionedAccount> findVersioned(Collection<UUID> ids) {
        return jdbc.sql("SELECT " + ACCOUNT_COLS + ", version FROM accounts WHERE id IN (:ids)")
                .param("ids", ids).query(VERSIONED_MAPPER).list();
    }

    @Override
    public Optional<UUID> insertTransaction(String idempotencyKey, String description, String requestHash) {
        UUID id = UUID.randomUUID();
        // Concurrent duplicates block on the unique index until the first commits, then no-op.
        int rows = jdbc.sql("""
                INSERT INTO ledger_transactions (id, idempotency_key, description, request_hash)
                VALUES (:id, :key, :desc, :hash)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", id).param("key", idempotencyKey).param("desc", description).param("hash", requestHash)
                .update();
        return rows == 1 ? Optional.of(id) : Optional.empty();
    }

    @Override
    public StoredTransaction findTransactionByKey(String idempotencyKey) {
        return jdbc.sql("SELECT id, request_hash FROM ledger_transactions WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, i) -> new StoredTransaction(rs.getObject("id", UUID.class), rs.getString("request_hash")))
                .single();
    }

    @Override
    public void insertEntry(UUID transactionId, UUID accountId, Direction direction, BigDecimal amount) {
        jdbc.sql("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) VALUES (:tx, :acc, :dir, :amt)")
                .param("tx", transactionId).param("acc", accountId)
                .param("dir", direction.name()).param("amt", amount)
                .update();
    }

    @Override
    public void updateBalance(UUID accountId, BigDecimal newBalance) {
        jdbc.sql("UPDATE accounts SET balance = :bal, version = version + 1 WHERE id = :id")
                .param("bal", newBalance).param("id", accountId).update();
    }

    @Override
    public boolean updateBalanceIfVersion(UUID accountId, BigDecimal newBalance, long expectedVersion) {
        int rows = jdbc.sql("UPDATE accounts SET balance = :bal, version = version + 1 WHERE id = :id AND version = :v")
                .param("bal", newBalance).param("id", accountId).param("v", expectedVersion).update();
        return rows == 1;
    }

    @Override
    public List<LedgerEntry> findEntries(UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT id, transaction_id, account_id, direction, amount, created_at
                FROM ledger_entries
                WHERE account_id = :account
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("account", accountId).param("limit", limit)
                .query(ENTRY_MAPPER).list();
    }

    @Override
    public List<BalanceMismatch> findBalanceMismatches() {
        return jdbc.sql(MISMATCH_SQL.formatted("")).query(MISMATCH_MAPPER).list();
    }

    @Override
    public List<BalanceMismatch> findBalanceMismatches(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(MISMATCH_SQL.formatted("WHERE a.id IN (:ids)"))
                .param("ids", accountIds).query(MISMATCH_MAPPER).list();
    }
}
