package com.portfolio.trading.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.trading.AccountNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls the ledger service over HTTP. Timeouts come from spring.http.client.* so a slow ledger fails fast and the
 * saga retries later instead of holding threads.
 */
@Component
class HttpLedgerClient implements LedgerClient {

    private final RestClient http;
    private final ServiceTokens tokens;
    private final Map<String, UUID> exchangeAccounts = new ConcurrentHashMap<>();

    HttpLedgerClient(RestClient.Builder builder, @Value("${ledger-client.base-url}") String baseUrl, ServiceTokens tokens) {
        this.http = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
    }

    @Override
    public AccountView getAccount(UUID accountId, String bearerToken) {
        try {
            AccountView view = http.get().uri("/api/v1/accounts/{id}", accountId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve().body(AccountView.class);
            if (view == null) {
                throw new LedgerUnavailableException("Ledger returned an empty account");
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new AccountNotFoundException(accountId);
            }
            throw failure("read account", e);
        } catch (ResourceAccessException e) {
            throw new LedgerUnavailableException("Ledger unreachable", e);
        }
    }

    @Override
    public UUID exchangeAccountId(String currency) {
        UUID cached = exchangeAccounts.get(currency);
        if (cached != null) {
            return cached;
        }
        try {
            AccountView view = http.post().uri("/api/v1/internal/exchange-accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("currency", currency))
                    .retrieve().body(AccountView.class);
            if (view == null) {
                throw new LedgerUnavailableException("Ledger returned an empty exchange account");
            }
            exchangeAccounts.put(currency, view.id());
            return view.id();
        } catch (RestClientResponseException e) {
            throw failure("look up the exchange account", e);
        } catch (ResourceAccessException e) {
            throw new LedgerUnavailableException("Ledger unreachable", e);
        }
    }

    @Override
    public void transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, BigDecimal amount, String description) {
        Map<String, Object> body = Map.of("fromAccountId", fromAccountId, "toAccountId", toAccountId,
                "amount", amount, "description", description);
        try {
            http.post().uri("/api/v1/internal/transfers")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = errorCode(e);
            if (status == 422 && "INSUFFICIENT_FUNDS".equals(code)) {
                throw new InsufficientFundsException(fromAccountId);
            }
            if (status == 404) {
                throw new AccountNotFoundException(fromAccountId);
            }
            if (status == 400 || status == 409 || status == 422) {
                throw new LedgerRejectedException("Ledger refused the transfer: " + code);
            }
            throw failure("transfer", e);   // 401, 403, 5xx: our problem or theirs, not a verdict on the trade
        } catch (ResourceAccessException e) {
            throw new LedgerUnavailableException("Ledger unreachable", e);
        }
    }

    private static LedgerUnavailableException failure(String what, RestClientResponseException e) {
        return new LedgerUnavailableException("Ledger could not " + what + " (HTTP " + e.getStatusCode().value() + ")", e);
    }

    /** The stable "code" property of the ledger's problem+json body, or "UNKNOWN". */
    private static String errorCode(RestClientResponseException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            return body == null ? "UNKNOWN" : body.path("code").asText("UNKNOWN");
        } catch (RuntimeException ignored) {
            return "UNKNOWN";
        }
    }
}
