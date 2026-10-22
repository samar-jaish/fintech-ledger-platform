package com.portfolio.ledger.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ledger.domain.TransferRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON field names are a contract with other services, so they are pinned here. */
class TransferCompletedTest {

    @Test
    void json_hasTheAgreedFields() throws Exception {
        UUID tx = UUID.randomUUID(), from = UUID.randomUUID(), to = UUID.randomUUID();
        var event = TransferCompleted.of(tx, new TransferRequest("k", from, to, new BigDecimal("12.50"), "lunch"));

        JsonNode json = new ObjectMapper().readTree(event.toJson());

        assertEquals(tx.toString(), json.get("transactionId").asText());
        assertEquals(from.toString(), json.get("fromAccountId").asText());
        assertEquals(to.toString(), json.get("toAccountId").asText());
        assertEquals(0, new BigDecimal("12.50").compareTo(json.get("amount").decimalValue()));
        assertEquals("lunch", json.get("description").asText());
        assertTrue(json.get("fromOwner").isNull());   // no owners given: house accounts
    }

    @Test
    void json_carriesTheOwnersOfBothAccounts() throws Exception {
        UUID tx = UUID.randomUUID(), from = UUID.randomUUID(), to = UUID.randomUUID();
        var event = TransferCompleted.of(tx, new TransferRequest("k", from, to, new BigDecimal("1"), null),
                java.util.Map.of(from, "user-a", to, "user-b"));

        JsonNode json = new ObjectMapper().readTree(event.toJson());

        assertEquals("user-a", json.get("fromOwner").asText());
        assertEquals("user-b", json.get("toOwner").asText());
    }
}
