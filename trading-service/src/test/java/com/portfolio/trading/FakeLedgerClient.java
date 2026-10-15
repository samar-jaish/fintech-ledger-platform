package com.portfolio.trading;

import com.portfolio.trading.ledger.AccountView;
import com.portfolio.trading.ledger.InsufficientFundsException;
import com.portfolio.trading.ledger.LedgerClient;
import com.portfolio.trading.ledger.LedgerRejectedException;
import com.portfolio.trading.ledger.LedgerUnavailableException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory ledger that can misbehave on demand, to test the saga's failure paths. Like the real ledger it applies
 * each idempotency key at most once, and it has an overdraft-allowed exchange account.
 */
class FakeLedgerClient implements LedgerClient {

    enum Mode {
        /** Works normally. */
        OK,
        /** Unreachable: the transfer is NOT applied. */
        DOWN,
        /** The transfer IS applied but the answer is lost (a timeout): the caller cannot tell. */
        LOST_RESPONSE,
        /** The ledger refuses every transfer. */
        REFUSE
    }

    record Call(String key, UUID from, UUID to, BigDecimal amount) { }

    final UUID exchange = UUID.randomUUID();
    volatile Mode mode = Mode.OK;
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Set<String> appliedKeys = ConcurrentHashMap.newKeySet();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    UUID fund(String amount) {
        UUID account = UUID.randomUUID();
        balances.put(account, new BigDecimal(amount));
        return account;
    }

    BigDecimal balance(UUID account) {
        return balances.getOrDefault(account, BigDecimal.ZERO);
    }

    List<Call> callsInvolving(UUID account) {
        return calls.stream().filter(c -> account.equals(c.from()) || account.equals(c.to())).toList();
    }

    @Override
    public AccountView getAccount(UUID accountId, String bearerToken) {
        if (!balances.containsKey(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return new AccountView(accountId, "owner", "USD", balance(accountId));
    }

    @Override
    public UUID exchangeAccountId(String currency) {
        return exchange;
    }

    @Override
    public synchronized void transfer(String key, UUID from, UUID to, BigDecimal amount, String description) {
        calls.add(new Call(key, from, to, amount));
        if (mode == Mode.DOWN) {
            throw new LedgerUnavailableException("fake ledger is down");
        }
        if (mode == Mode.REFUSE) {
            throw new LedgerRejectedException("fake ledger refuses");
        }
        if (appliedKeys.add(key)) {                       // first time we see this key
            if (!from.equals(exchange) && balance(from).compareTo(amount) < 0) {
                appliedKeys.remove(key);
                throw new InsufficientFundsException(from);
            }
            balances.merge(from, amount.negate(), BigDecimal::add);
            balances.merge(to, amount, BigDecimal::add);
        }
        if (mode == Mode.LOST_RESPONSE) {
            throw new LedgerUnavailableException("fake ledger answer lost");
        }
    }
}
