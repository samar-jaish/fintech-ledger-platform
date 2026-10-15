package com.portfolio.ledger.api;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.AccountNotFoundException;
import com.portfolio.ledger.domain.IdempotencyKeyReuseException;
import com.portfolio.ledger.domain.InsufficientFundsException;
import com.portfolio.ledger.domain.SystemAccounts;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.domain.TransferResult;
import com.portfolio.ledger.market.MarketDataException;
import com.portfolio.ledger.service.LedgerService;
import com.portfolio.ledger.trading.InsufficientHoldingsException;
import com.portfolio.ledger.trading.Portfolio;
import com.portfolio.ledger.trading.PortfolioService;
import com.portfolio.ledger.trading.Side;
import com.portfolio.ledger.trading.Trade;
import com.portfolio.ledger.trading.TradeRequest;
import com.portfolio.ledger.trading.TradeResult;
import com.portfolio.ledger.trading.TradeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer only: HTTP mapping, status codes and error format. Services and the ownership check are mocked;
 * every request runs as the signed-in user "alice" (see SecurityRulesTest and ApiIntegrationTest for security).
 */
@WebMvcTest({LedgerController.class, TradingController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "alice")
class ApiControllersTest {

    @Autowired MockMvc mvc;
    @MockitoBean LedgerService ledger;
    @MockitoBean TradeService trading;
    @MockitoBean PortfolioService portfolio;
    @MockitoBean AccountAccess access;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static String json(String s) {
        return s.replace('\'', '"');
    }

    @Test
    void createAccount_returns201_withLocation() throws Exception {
        when(ledger.openAccountFor("alice", "Alice", "USD")).thenReturn(new Account(ID, "Alice", "USD", BigDecimal.ZERO, false));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'ownerName':'Alice','currency':'USD'}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void createAccount_withBadCurrency_returns400_problemJson() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'ownerName':'Alice','currency':'usd'}")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(ledger);
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidUuidInPath_returns400() throws Exception {
        mvc.perform(get("/api/v1/accounts/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void getAccount_unknown_returns404() throws Exception {
        when(ledger.getAccount(ID)).thenThrow(new AccountNotFoundException(ID));

        mvc.perform(get("/api/v1/accounts/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void transfer_new_returns201_andReplay_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(ledger.transfer(any(TransferRequest.class)))
                .thenReturn(new TransferResult(txId, false))
                .thenReturn(new TransferResult(txId, true));
        String body = json("{'fromAccountId':'" + ID + "','toAccountId':'" + OTHER + "','amount':25.50}");

        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        ArgumentCaptor<TransferRequest> captor = ArgumentCaptor.forClass(TransferRequest.class);
        verify(ledger, org.mockito.Mockito.times(2)).transfer(captor.capture());
        assertEquals("k1", captor.getValue().idempotencyKey());
        assertEquals(0, new BigDecimal("25.50").compareTo(captor.getValue().amount()));
    }

    @Test
    void transfer_withoutIdempotencyKey_returns400() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'fromAccountId':'" + ID + "','toAccountId':'" + OTHER + "','amount':1}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ledger);
    }

    @Test
    void transfer_withMissingOrInvalidFields_returns400() throws Exception {
        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'fromAccountId':'" + ID + "','amount':1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("toAccountId is required"));
        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'fromAccountId':'" + ID + "','toAccountId':'" + OTHER + "','amount':-5}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "x".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'fromAccountId':'" + ID + "','toAccountId':'" + OTHER + "','amount':1}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ledger);
    }

    @Test
    void transfer_insufficientFunds_returns422() throws Exception {
        when(ledger.transfer(any())).thenThrow(new InsufficientFundsException(ID));

        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'fromAccountId':'" + ID + "','toAccountId':'" + OTHER + "','amount':1}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void transfer_keyReuseWithDifferentRequest_returns409() throws Exception {
        when(ledger.transfer(any())).thenThrow(new IdempotencyKeyReuseException("k"));

        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'fromAccountId':'" + ID + "','toAccountId':'" + OTHER + "','amount':1}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void deposit_usesHouseAccount_andPrefixesTheKey() throws Exception {
        when(ledger.transfer(any())).thenReturn(new TransferResult(UUID.randomUUID(), false));

        mvc.perform(post("/api/v1/accounts/" + ID + "/deposits").header("Idempotency-Key", "d1")
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'amount':1000}")))
                .andExpect(status().isCreated());

        ArgumentCaptor<TransferRequest> captor = ArgumentCaptor.forClass(TransferRequest.class);
        verify(ledger).transfer(captor.capture());
        assertEquals(SystemAccounts.HOUSE_USD, captor.getValue().fromAccountId());
        assertEquals(ID, captor.getValue().toAccountId());
        assertEquals("deposit:d1", captor.getValue().idempotencyKey());
    }

    @Test
    void statement_returnsEntries_andPassesLimit() throws Exception {
        when(ledger.getStatement(ID, 5)).thenReturn(List.of());

        mvc.perform(get("/api/v1/accounts/" + ID + "/statement?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        verify(ledger).getStatement(ID, 5);
    }

    @Test
    void statement_withNonNumericLimit_returns400() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + ID + "/statement?limit=abc")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reconciliation_reportsConsistent() throws Exception {
        when(ledger.reconcile()).thenReturn(List.of());

        mvc.perform(get("/api/v1/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.mismatches").isEmpty());
    }

    @Test
    void trade_returns201_withPriceAndSource() throws Exception {
        Trade trade = new Trade(UUID.randomUUID(), "t1", "hash", ID, "AAPL", Side.BUY, new BigDecimal("2"),
                new BigDecimal("100.0000"), new BigDecimal("200.0000"), "USD", "simulated", Instant.now(), Instant.now());
        when(trading.execute(any(TradeRequest.class))).thenReturn(new TradeResult(trade, false));

        mvc.perform(post("/api/v1/trades").header("Idempotency-Key", "t1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'accountId':'" + ID + "','symbol':'aapl','side':'BUY','quantity':2}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.priceSource").value("simulated"))
                .andExpect(jsonPath("$.requestHash").doesNotExist())
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void trade_withUnknownSide_returns400() throws Exception {
        mvc.perform(post("/api/v1/trades").header("Idempotency-Key", "t1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'accountId':'" + ID + "','symbol':'AAPL','side':'HOLD','quantity':2}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(trading);
    }

    @Test
    void trade_insufficientHoldings_returns422() throws Exception {
        when(trading.execute(any())).thenThrow(new InsufficientHoldingsException(ID, "AAPL"));

        mvc.perform(post("/api/v1/trades").header("Idempotency-Key", "t1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'accountId':'" + ID + "','symbol':'AAPL','side':'SELL','quantity':2}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_HOLDINGS"));
    }

    @Test
    void trade_whenMarketDown_returns503_withoutLeakingDetails() throws Exception {
        when(trading.execute(any())).thenThrow(new MarketDataException("vendor said: token=SECRET"));

        mvc.perform(post("/api/v1/trades").header("Idempotency-Key", "t1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'accountId':'" + ID + "','symbol':'AAPL','side':'BUY','quantity':2}")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
    }

    @Test
    void unexpectedError_returns500_withoutDetails() throws Exception {
        when(ledger.getAccount(ID)).thenThrow(new IllegalStateException("db password is hunter2"));

        mvc.perform(get("/api/v1/accounts/" + ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
    }

    @Test
    void portfolio_returnsValuedPositions() throws Exception {
        when(portfolio.getPortfolio(ID)).thenReturn(new Portfolio(ID, "USD", new BigDecimal("800.0000"), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("800.0000"), true));

        mvc.perform(get("/api/v1/accounts/" + ID + "/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.cash").value(800.0));
    }

    @Test
    void trades_list_returnsNewestFirstFromService() throws Exception {
        when(portfolio.getTrades(eq(ID), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/accounts/" + ID + "/trades")).andExpect(status().isOk());
        verify(portfolio).getTrades(ID, 50);
    }
}
