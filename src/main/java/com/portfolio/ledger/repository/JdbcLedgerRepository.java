package com.portfolio.ledger.repository;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.Direction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    private final JdbcClient jdbc;

    public JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Account createAccount(String ownerName, String currency, boolean allowNegative) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, owner_name, currency, allow_negative) VALUES (:id, :owner, :cur, :neg)")
                .param("id", id).param("owner", ownerName).param("cur", currency).param("neg", allowNegative)
                .update();
        return findAccount(id).orElseThrow();
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
    public Optional<UUID> insertTransaction(String idempotencyKey, String description) {
        UUID id = UUID.randomUUID();
        // Concurrent duplicates block on the unique index until the first commits, then no-op.
        int rows = jdbc.sql("""
                INSERT INTO ledger_transactions (id, idempotency_key, description)
                VALUES (:id, :key, :desc)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", id).param("key", idempotencyKey).param("desc", description)
                .update();
        return rows == 1 ? Optional.of(id) : Optional.empty();
    }

    @Override
    public UUID findTransactionIdByKey(String idempotencyKey) {
        return jdbc.sql("SELECT id FROM ledger_transactions WHERE idempotency_key = :key")
                .param("key", idempotencyKey).query(UUID.class).single();
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
        jdbc.sql("UPDATE accounts SET balance = :bal WHERE id = :id")
                .param("bal", newBalance).param("id", accountId).update();
    }
}
