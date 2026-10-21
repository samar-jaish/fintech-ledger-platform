package com.portfolio.trading.api;

import com.portfolio.trading.PortfolioService;
import com.portfolio.trading.TradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The web UI runs on another origin, so the browser sends a preflight request first. Only the listed origin may pass. */
@WebMvcTest(TradingController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:5173")
class CorsTest {

    private static final String UI = "http://localhost:5173";

    @Autowired MockMvc mvc;
    @MockitoBean TradeService trading;
    @MockitoBean PortfolioService portfolio;
    @MockitoBean AccountAccess access;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void preflightFromTheUi_isAnswered_withoutAToken() throws Exception {
        mvc.perform(options("/api/v1/trades")
                        .header("Origin", UI)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", UI));
    }

    @Test
    void preflightFromAnotherOrigin_isRejected() throws Exception {
        mvc.perform(options("/api/v1/trades")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void errorsReachTheUi_soItCanReadThem() throws Exception {
        // A 401 without CORS headers would look like a network failure in the browser, hiding the real reason.
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()).header("Origin", UI))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", UI));
    }
}
