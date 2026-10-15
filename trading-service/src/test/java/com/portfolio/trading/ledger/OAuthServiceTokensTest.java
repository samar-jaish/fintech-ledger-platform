package com.portfolio.trading.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuthServiceTokensTest {

    private static final String TOKEN_URL = "http://keycloak/token";

    /** A clock the test can move forward. */
    static class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final MovableClock clock = new MovableClock();
    private final OAuthServiceTokens tokens = new OAuthServiceTokens(builder, TOKEN_URL, "trading-service", "secret", clock);

    private static org.springframework.test.web.client.response.DefaultResponseCreator token(String value, int expiresIn) {
        return withSuccess("{\"access_token\":\"" + value + "\",\"expires_in\":" + expiresIn + "}", MediaType.APPLICATION_JSON);
    }

    @Test
    void tokenIsFetchedWithClientCredentials_andReusedWhileValid() {
        server.expect(once(), requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("grant_type", "client_credentials",
                        "client_id", "trading-service", "client_secret", "secret")))
                .andRespond(token("t1", 300));

        assertEquals("t1", tokens.token());
        clock.advance(Duration.ofSeconds(100));
        assertEquals("t1", tokens.token());   // no second request
        server.verify();
    }

    @Test
    void tokenIsRenewedShortlyBeforeItExpires() {
        server.expect(once(), requestTo(TOKEN_URL)).andRespond(token("t1", 300));
        server.expect(once(), requestTo(TOKEN_URL)).andRespond(token("t2", 300));

        assertEquals("t1", tokens.token());
        clock.advance(Duration.ofSeconds(280));   // inside the 30 s renewal margin
        assertEquals("t2", tokens.token());
        server.verify();
    }

    @Test
    void identityProviderFailure_isReportedAsLedgerUnavailable() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThrows(LedgerUnavailableException.class, tokens::token);
    }
}
