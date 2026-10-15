package com.portfolio.ledger.api;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.BalanceMismatch;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.trading.Side;
import com.portfolio.ledger.trading.Trade;
import com.portfolio.ledger.trading.TradeRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Request and response bodies of the REST API. Requests validate themselves and throw IllegalArgumentException (HTTP 400). */
final class ApiDtos {

    private static final int MAX_KEY_LENGTH = 64;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private ApiDtos() { }

    record CreateAccountBody(String ownerName, String currency) {
        void validate() {
            if (ownerName == null || ownerName.isBlank() || ownerName.length() > 100) {
                throw new IllegalArgumentException("ownerName is required (max 100 characters)");
            }
            if (currency == null || !CURRENCY.matcher(currency).matches()) {
                throw new IllegalArgumentException("currency must be a 3-letter upper-case code, e.g. USD");
            }
        }
    }

    record DepositBody(BigDecimal amount) { }

    record TransferBody(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String description) {
        TransferRequest toRequest(String idempotencyKey) {
            return new TransferRequest(key(idempotencyKey), required(fromAccountId, "fromAccountId"),
                    required(toAccountId, "toAccountId"), required(amount, "amount"), description);
        }
    }

    record TradeBody(UUID accountId, String symbol, Side side, BigDecimal quantity) {
        TradeRequest toRequest(String idempotencyKey) {
            return new TradeRequest(key(idempotencyKey), required(accountId, "accountId"), required(symbol, "symbol"),
                    required(side, "side"), required(quantity, "quantity"));
        }
    }

    record AccountResponse(UUID id, String ownerName, String currency, BigDecimal balance) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.id(), a.ownerName(), a.currency(), a.balance());
        }
    }

    record TransferResponse(UUID transactionId, boolean replayed) { }

    record TradeResponse(UUID id, UUID accountId, String symbol, Side side, BigDecimal quantity, BigDecimal price,
                         BigDecimal notional, String currency, String priceSource, Instant priceAsOf,
                         Instant executedAt, boolean replayed) {
        static TradeResponse from(Trade t, boolean replayed) {
            return new TradeResponse(t.id(), t.accountId(), t.symbol(), t.side(), t.quantity(), t.price(),
                    t.notional(), t.currency(), t.priceSource(), t.priceAsOf(), t.executedAt(), replayed);
        }
    }

    record ReconciliationResponse(boolean consistent, List<BalanceMismatch> mismatches) { }

    static String key(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return idempotencyKey;
    }

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
