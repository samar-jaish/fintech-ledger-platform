package com.portfolio.trading.api;

import com.portfolio.trading.AccountNotFoundException;
import com.portfolio.trading.PortfolioService;
import com.portfolio.trading.Side;
import com.portfolio.trading.Trade;
import com.portfolio.trading.TradeRejectedException;
import com.portfolio.trading.TradeRequest;
import com.portfolio.trading.TradeResult;
import com.portfolio.trading.TradeService;
import com.portfolio.trading.TradeStatus;
import com.portfolio.trading.ledger.LedgerUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only: status codes and error format. Services and the ownership check are mocked. */
@WebMvcTest(TradingController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "alice")
class TradingControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TradeService trading;
    @MockitoBean PortfolioService portfolio;
    @MockitoBean AccountAccess access;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.randomUUID();
    private static final String BODY = "{\"accountId\":\"" + ID + "\",\"symbol\":\"aapl\",\"side\":\"BUY\",\"quantity\":2}";

    private static Trade trade(TradeStatus status) {
        return new Trade(UUID.randomUUID(), "t1", "hash", ID, UUID.randomUUID(), "AAPL", Side.BUY, new BigDecimal("2"),
                new BigDecimal("100.0000"), new BigDecimal("200.0000"), "USD", "simulated", Instant.now(), Instant.now(),
                status, null);
    }

    private org.springframework.test.web.servlet.ResultActions postTrade(String body) throws Exception {
        return mvc.perform(post("/api/v1/trades").header("Idempotency-Key", "t1").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void completedTrade_returns201_withPriceSourceAndStatus() throws Exception {
        when(trading.execute(any(TradeRequest.class))).thenReturn(new TradeResult(trade(TradeStatus.COMPLETED), false));

        postTrade(BODY).andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.priceSource").value("simulated"))
                .andExpect(jsonPath("$.requestHash").doesNotExist());
    }

    @Test
    void replayedTrade_returns200() throws Exception {
        when(trading.execute(any())).thenReturn(new TradeResult(trade(TradeStatus.COMPLETED), true));
        postTrade(BODY).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void pendingTrade_returns202() throws Exception {
        when(trading.execute(any())).thenReturn(new TradeResult(trade(TradeStatus.PENDING), false));
        postTrade(BODY).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectedTrade_returns422_withTheReasonCode() throws Exception {
        when(trading.execute(any())).thenThrow(new TradeRejectedException("INSUFFICIENT_FUNDS", "rejected"));
        postTrade(BODY).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void ledgerDown_returns503_withoutDetails() throws Exception {
        when(trading.execute(any())).thenThrow(new LedgerUnavailableException("connect to 10.0.0.5 refused"));
        postTrade(BODY).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LEDGER_UNAVAILABLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("10.0.0.5"))));
    }

    @Test
    void unknownSide_returns400_andNothingIsCalled() throws Exception {
        postTrade(BODY.replace("BUY", "HOLD")).andExpect(status().isBadRequest());
        verifyNoInteractions(trading);
    }

    @Test
    void someoneElsesAccount_looksLikeItDoesNotExist() throws Exception {
        doThrow(new AccountNotFoundException(ID)).when(access).require(any(), any());
        postTrade(BODY).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        verifyNoInteractions(trading);
    }

    @Test
    @WithAnonymousUser
    void noToken_returns401() throws Exception {
        postTrade(BODY).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
