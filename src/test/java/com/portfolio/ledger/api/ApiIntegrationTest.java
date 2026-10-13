package com.portfolio.ledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole stack over HTTP (MockMvc) against a real Postgres, with the simulated market. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body.replace('\'', '"'));
    }

    private String createAccount(String owner) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/v1/accounts"), "{'ownerName':'" + owner + "','currency':'USD'}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private void deposit(String accountId, String amount, String key) throws Exception {
        mvc.perform(json(post("/api/v1/accounts/" + accountId + "/deposits").header("Idempotency-Key", key),
                "{'amount':" + amount + "}"));
    }

    @Test
    void depositThenGetAccount_showsBalance_andReplayDoesNotDoubleCredit() throws Exception {
        String id = createAccount("alice");
        String key = uniqueKey();

        mvc.perform(json(post("/api/v1/accounts/" + id + "/deposits").header("Idempotency-Key", key), "{'amount':1000}"))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/accounts/" + id + "/deposits").header("Idempotency-Key", key), "{'amount':1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(get("/api/v1/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.0));
    }

    @Test
    void transferBetweenAccounts_updatesBothBalances_andStatementShowsEntries() throws Exception {
        String alice = createAccount("alice");
        String bob = createAccount("bob");
        deposit(alice, "500", uniqueKey());

        mvc.perform(json(post("/api/v1/transfers").header("Idempotency-Key", uniqueKey()),
                        "{'fromAccountId':'" + alice + "','toAccountId':'" + bob + "','amount':120.25}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/accounts/" + alice)).andExpect(jsonPath("$.balance").value(379.75));
        mvc.perform(get("/api/v1/accounts/" + bob)).andExpect(jsonPath("$.balance").value(120.25));
        mvc.perform(get("/api/v1/accounts/" + bob + "/statement?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").value("CREDIT"));
    }

    @Test
    void overdraft_returns422_andLeavesBalanceUnchanged() throws Exception {
        String alice = createAccount("alice");
        String bob = createAccount("bob");
        deposit(alice, "10", uniqueKey());

        mvc.perform(json(post("/api/v1/transfers").header("Idempotency-Key", uniqueKey()),
                        "{'fromAccountId':'" + alice + "','toAccountId':'" + bob + "','amount':50}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        mvc.perform(get("/api/v1/accounts/" + alice)).andExpect(jsonPath("$.balance").value(10.0));
    }

    @Test
    void sameKeyDifferentAmount_returns409() throws Exception {
        String alice = createAccount("alice");
        String bob = createAccount("bob");
        deposit(alice, "500", uniqueKey());
        String key = uniqueKey();

        mvc.perform(json(post("/api/v1/transfers").header("Idempotency-Key", key),
                "{'fromAccountId':'" + alice + "','toAccountId':'" + bob + "','amount':10}")).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/transfers").header("Idempotency-Key", key),
                        "{'fromAccountId':'" + alice + "','toAccountId':'" + bob + "','amount':20}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownAccount_returns404_forGetDepositAndStatement() throws Exception {
        String missing = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/accounts/" + missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/" + missing + "/statement")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/accounts/" + missing + "/deposits").header("Idempotency-Key", uniqueKey()),
                "{'amount':5}")).andExpect(status().isNotFound());
    }

    @Test
    void tradeFlow_buyThenPortfolio_thenSellTooMuchIsRejected() throws Exception {
        String id = createAccount("trader");
        deposit(id, "100000", uniqueKey());

        mvc.perform(json(post("/api/v1/trades").header("Idempotency-Key", uniqueKey()),
                        "{'accountId':'" + id + "','symbol':'AAPL','side':'BUY','quantity':10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceSource").value("simulated"));

        MvcResult portfolio = mvc.perform(get("/api/v1/accounts/" + id + "/portfolio"))
                .andExpect(status().isOk()).andReturn();
        JsonNode p = mapper.readTree(portfolio.getResponse().getContentAsString());
        assertTrue(p.get("complete").asBoolean());
        assertEquals(1, p.get("positions").size());
        assertEquals("AAPL", p.get("positions").get(0).get("symbol").asText());

        mvc.perform(json(post("/api/v1/trades").header("Idempotency-Key", uniqueKey()),
                        "{'accountId':'" + id + "','symbol':'AAPL','side':'SELL','quantity':50}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_HOLDINGS"));

        mvc.perform(get("/api/v1/accounts/" + id + "/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void tradeReplay_returns200_andSameTradeId() throws Exception {
        String id = createAccount("trader");
        deposit(id, "100000", uniqueKey());
        String key = uniqueKey();
        String body = "{'accountId':'" + id + "','symbol':'MSFT','side':'BUY','quantity':1}";

        MvcResult first = mvc.perform(json(post("/api/v1/trades").header("Idempotency-Key", key), body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mvc.perform(json(post("/api/v1/trades").header("Idempotency-Key", key), body))
                .andExpect(status().isOk()).andReturn();

        assertEquals(mapper.readTree(first.getResponse().getContentAsString()).get("id"),
                mapper.readTree(second.getResponse().getContentAsString()).get("id"));
    }

    @Test
    void reconciliation_isConsistentAfterActivity() throws Exception {
        String id = createAccount("alice");
        deposit(id, "250", uniqueKey());

        mvc.perform(get("/api/v1/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }
}
