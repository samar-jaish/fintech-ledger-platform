package com.portfolio.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole stack over HTTP (MockMvc) against a real Postgres, with the simulated market.
 * Each test uses its own users; the JWT post-processor stands in for a token already validated by Keycloak.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String newUser(String name) {
        return name + "-" + UUID.randomUUID();
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body.replace('\'', '"'));
    }

    private String createAccount(String subject) throws Exception {
        MvcResult r = mvc.perform(withBody(post("/api/v1/accounts").with(as(subject)),
                        "{'ownerName':'" + subject + "','currency':'USD'}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private void deposit(String subject, String accountId, String amount) throws Exception {
        mvc.perform(withBody(post("/api/v1/accounts/" + accountId + "/deposits").with(as(subject))
                        .header("Idempotency-Key", uniqueKey()), "{'amount':" + amount + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void requestsWithoutAToken_are401() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(withBody(post("/api/v1/accounts"), "{'ownerName':'x','currency':'USD'}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listMyAccounts_returnsOnlyTheCallersAccounts_oldestFirst() throws Exception {
        String alice = newUser("alice");
        String bob = newUser("bob");
        String first = createAccount(alice);
        String second = createAccount(alice);
        createAccount(bob);
        deposit(alice, second, "40");

        mvc.perform(get("/api/v1/accounts").with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[1].balance").value(40.0));
    }

    @Test
    void listMyAccounts_isEmptyForAUserWithNoAccounts() throws Exception {
        mvc.perform(get("/api/v1/accounts").with(as(newUser("carol"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void depositThenGetAccount_showsBalance_andReplayDoesNotDoubleCredit() throws Exception {
        String alice = newUser("alice");
        String id = createAccount(alice);
        String key = uniqueKey();

        mvc.perform(withBody(post("/api/v1/accounts/" + id + "/deposits").with(as(alice))
                        .header("Idempotency-Key", key), "{'amount':1000}"))
                .andExpect(status().isCreated());
        mvc.perform(withBody(post("/api/v1/accounts/" + id + "/deposits").with(as(alice))
                        .header("Idempotency-Key", key), "{'amount':1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(get("/api/v1/accounts/" + id).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.0));
    }

    @Test
    void transfer_fromOwnAccount_toAnotherUsersAccount_updatesBothBalances() throws Exception {
        String alice = newUser("alice");
        String bob = newUser("bob");
        String aliceId = createAccount(alice);
        String bobId = createAccount(bob);
        deposit(alice, aliceId, "500");

        mvc.perform(withBody(post("/api/v1/transfers").with(as(alice)).header("Idempotency-Key", uniqueKey()),
                        "{'fromAccountId':'" + aliceId + "','toAccountId':'" + bobId + "','amount':120.25}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/accounts/" + aliceId).with(as(alice))).andExpect(jsonPath("$.balance").value(379.75));
        mvc.perform(get("/api/v1/accounts/" + bobId).with(as(bob))).andExpect(jsonPath("$.balance").value(120.25));
        mvc.perform(get("/api/v1/accounts/" + bobId + "/statement?limit=10").with(as(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").value("CREDIT"));
    }

    @Test
    void overdraft_returns422_andLeavesBalanceUnchanged() throws Exception {
        String alice = newUser("alice");
        String aliceId = createAccount(alice);
        String bobId = createAccount(newUser("bob"));
        deposit(alice, aliceId, "10");

        mvc.perform(withBody(post("/api/v1/transfers").with(as(alice)).header("Idempotency-Key", uniqueKey()),
                        "{'fromAccountId':'" + aliceId + "','toAccountId':'" + bobId + "','amount':50}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        mvc.perform(get("/api/v1/accounts/" + aliceId).with(as(alice))).andExpect(jsonPath("$.balance").value(10.0));
    }

    @Test
    void sameKeyDifferentAmount_returns409() throws Exception {
        String alice = newUser("alice");
        String aliceId = createAccount(alice);
        String bobId = createAccount(newUser("bob"));
        deposit(alice, aliceId, "500");
        String key = uniqueKey();

        mvc.perform(withBody(post("/api/v1/transfers").with(as(alice)).header("Idempotency-Key", key),
                "{'fromAccountId':'" + aliceId + "','toAccountId':'" + bobId + "','amount':10}")).andExpect(status().isCreated());
        mvc.perform(withBody(post("/api/v1/transfers").with(as(alice)).header("Idempotency-Key", key),
                        "{'fromAccountId':'" + aliceId + "','toAccountId':'" + bobId + "','amount':20}"))
                .andExpect(status().isConflict());
    }

    @Test
    void someoneElsesAccount_looksLikeItDoesNotExist() throws Exception {
        String alice = newUser("alice");
        String mallory = newUser("mallory");
        String aliceId = createAccount(alice);
        deposit(alice, aliceId, "1000");
        String malloryId = createAccount(mallory);

        mvc.perform(get("/api/v1/accounts/" + aliceId).with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(withBody(post("/api/v1/accounts/" + aliceId + "/deposits").with(as(mallory))
                .header("Idempotency-Key", uniqueKey()), "{'amount':5}")).andExpect(status().isNotFound());
        mvc.perform(withBody(post("/api/v1/transfers").with(as(mallory)).header("Idempotency-Key", uniqueKey()),
                "{'fromAccountId':'" + aliceId + "','toAccountId':'" + malloryId + "','amount':100}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/accounts/" + aliceId).with(as(alice))).andExpect(jsonPath("$.balance").value(1000.0));
    }

    @Test
    void admin_canReadAnyAccount_andUnknownAccountsAre404() throws Exception {
        String alice = newUser("alice");
        String aliceId = createAccount(alice);

        mvc.perform(get("/api/v1/accounts/" + aliceId).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void reconciliation_isAdminOnly_andConsistentAfterActivity() throws Exception {
        String alice = newUser("alice");
        deposit(alice, createAccount(alice), "250");

        mvc.perform(get("/api/v1/reconciliation").with(as(alice))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reconciliation").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    private static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("service-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    @Test
    void internalEndpoints_needTheServiceRole() throws Exception {
        String alice = newUser("alice");
        String id = createAccount(alice);
        String body = "{'fromAccountId':'" + id + "','toAccountId':'" + UUID.randomUUID() + "','amount':1}";

        mvc.perform(withBody(post("/api/v1/internal/transfers").with(as(alice)).header("Idempotency-Key", uniqueKey()), body))
                .andExpect(status().isForbidden());
        mvc.perform(withBody(post("/api/v1/internal/transfers").with(admin()).header("Idempotency-Key", uniqueKey()), body))
                .andExpect(status().isForbidden());
        mvc.perform(withBody(post("/api/v1/internal/exchange-accounts").with(as(alice)), "{'currency':'USD'}"))
                .andExpect(status().isForbidden());
        mvc.perform(withBody(post("/api/v1/internal/exchange-accounts"), "{'currency':'USD'}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void service_canMoveMoneyBothWaysWithTheExchange_idempotently() throws Exception {
        String trader = newUser("trader");
        String id = createAccount(trader);
        deposit(trader, id, "1000");

        MvcResult ex = mvc.perform(withBody(post("/api/v1/internal/exchange-accounts").with(service()), "{'currency':'USD'}"))
                .andExpect(status().isOk()).andReturn();
        String exchangeId = mapper.readTree(ex.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(withBody(post("/api/v1/internal/exchange-accounts").with(service()), "{'currency':'USD'}"))
                .andExpect(jsonPath("$.id").value(exchangeId));   // same account every time

        String key = "trade:" + UUID.randomUUID();
        String buy = "{'fromAccountId':'" + id + "','toAccountId':'" + exchangeId + "','amount':300,'description':'BUY 3 X'}";
        mvc.perform(withBody(post("/api/v1/internal/transfers").with(service()).header("Idempotency-Key", key), buy))
                .andExpect(status().isCreated());
        mvc.perform(withBody(post("/api/v1/internal/transfers").with(service()).header("Idempotency-Key", key), buy))
                .andExpect(status().isOk());   // replay: not applied twice
        mvc.perform(get("/api/v1/accounts/" + id).with(as(trader))).andExpect(jsonPath("$.balance").value(700.0));

        String sell = "{'fromAccountId':'" + exchangeId + "','toAccountId':'" + id + "','amount':100,'description':'SELL 1 X'}";
        mvc.perform(withBody(post("/api/v1/internal/transfers").with(service()).header("Idempotency-Key", "trade:" + UUID.randomUUID()), sell))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/accounts/" + id).with(as(trader))).andExpect(jsonPath("$.balance").value(800.0));

        String tooMuch = "{'fromAccountId':'" + id + "','toAccountId':'" + exchangeId + "','amount':999999}";
        mvc.perform(withBody(post("/api/v1/internal/transfers").with(service()).header("Idempotency-Key", uniqueKey()), tooMuch))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }
}
