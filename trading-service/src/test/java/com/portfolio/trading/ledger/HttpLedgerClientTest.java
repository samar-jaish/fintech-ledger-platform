package com.portfolio.trading.ledger;

import com.portfolio.trading.AccountNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpMethod;

/** The HTTP client against a mock server: what it sends, and how each kind of answer is classified for the saga. */
class HttpLedgerClientTest {

    private static final String TRANSFERS = "http://ledger/api/v1/internal/transfers";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpLedgerClient client = new HttpLedgerClient(builder, "http://ledger", () -> "service-token");

    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private void transfer() {
        client.transfer("trade:1", from, to, new BigDecimal("300.0000"), "BUY 3 AAPL");
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator problem(HttpStatus status, String code) {
        return withStatus(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"" + code + "\"}");
    }

    @Test
    void transfer_sendsTheServiceTokenTheKeyAndTheAccounts() {
        server.expect(requestTo(TRANSFERS)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer service-token"))
                .andExpect(header("Idempotency-Key", "trade:1"))
                .andExpect(jsonPath("$.fromAccountId").value(from.toString()))
                .andExpect(jsonPath("$.toAccountId").value(to.toString()))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));

        transfer();

        server.verify();
    }

    @Test
    void insufficientFunds_isAFinalAnswer() {
        server.expect(requestTo(TRANSFERS)).andRespond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS"));
        assertThrows(InsufficientFundsException.class, this::transfer);
    }

    @Test
    void otherRefusals_areFinalToo() {
        server.expect(requestTo(TRANSFERS)).andRespond(problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        assertThrows(LedgerRejectedException.class, this::transfer);
    }

    @Test
    void unknownAccount_isFinal() {
        server.expect(requestTo(TRANSFERS)).andRespond(problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"));
        assertThrows(AccountNotFoundException.class, this::transfer);
    }

    @Test
    void serverErrors_authProblems_andNetworkFailures_meanUnknown_soTheSagaRetries() {
        server.expect(requestTo(TRANSFERS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(LedgerUnavailableException.class, this::transfer);

        server.reset();
        server.expect(requestTo(TRANSFERS)).andRespond(problem(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        assertThrows(LedgerUnavailableException.class, this::transfer);

        server.reset();
        server.expect(requestTo(TRANSFERS)).andRespond(withException(new IOException("connection reset")));
        assertThrows(LedgerUnavailableException.class, this::transfer);
    }

    @Test
    void exchangeAccount_isLookedUpOnce_thenCached() {
        UUID exchange = UUID.randomUUID();
        server.expect(once(), requestTo("http://ledger/api/v1/internal/exchange-accounts"))
                .andExpect(header("Authorization", "Bearer service-token"))
                .andRespond(withSuccess("{\"id\":\"" + exchange + "\",\"ownerName\":\"EXCHANGE\",\"currency\":\"USD\",\"balance\":0}",
                        MediaType.APPLICATION_JSON));

        assertEquals(exchange, client.exchangeAccountId("USD"));
        assertEquals(exchange, client.exchangeAccountId("USD"));
        server.verify();
    }

    @Test
    void getAccount_passesOnTheCallersToken_andMapsNotFound() {
        server.expect(requestTo("http://ledger/api/v1/accounts/" + from))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess("{\"id\":\"" + from + "\",\"ownerName\":\"Alice\",\"currency\":\"USD\",\"balance\":12.5}",
                        MediaType.APPLICATION_JSON));
        assertEquals(0, new BigDecimal("12.5").compareTo(client.getAccount(from, "user-token").balance()));

        server.reset();
        server.expect(requestTo("http://ledger/api/v1/accounts/" + to)).andRespond(problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"));
        assertThrows(AccountNotFoundException.class, () -> client.getAccount(to, "user-token"));
    }
}
