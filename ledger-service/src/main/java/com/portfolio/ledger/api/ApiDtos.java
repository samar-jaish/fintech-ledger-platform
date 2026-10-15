package com.portfolio.ledger.api;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.BalanceMismatch;
import com.portfolio.ledger.domain.TransferRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Request and response bodies of the REST API. Requests validate themselves and throw IllegalArgumentException (HTTP 400). */
final class ApiDtos {

    private static final int MAX_KEY_LENGTH = 64;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private ApiDtos() { }

    /** Throws IllegalArgumentException (HTTP 400) unless the value is a 3-letter upper-case currency code. */
    static final java.util.function.Consumer<String> CURRENCY_CHECK = currency -> {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be a 3-letter upper-case code, e.g. USD");
        }
    };

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

    record AccountResponse(UUID id, String ownerName, String currency, BigDecimal balance) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.id(), a.ownerName(), a.currency(), a.balance());
        }
    }

    record TransferResponse(UUID transactionId, boolean replayed) { }

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
