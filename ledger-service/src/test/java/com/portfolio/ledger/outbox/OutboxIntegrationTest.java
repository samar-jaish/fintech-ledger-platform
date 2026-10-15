package com.portfolio.ledger.outbox;

import com.portfolio.ledger.domain.Account;
import com.portfolio.ledger.domain.InsufficientFundsException;
import com.portfolio.ledger.domain.SystemAccounts;
import com.portfolio.ledger.domain.TransferRequest;
import com.portfolio.ledger.service.LedgerService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Postgres and a real Kafka broker. Covers both halves: the event is written atomically with the transfer
 * (or not at all), and the relay then delivers it to Kafka to the topic.
 */
@SpringBootTest(properties = {"ledger.outbox.relay.enabled=true", "ledger.outbox.relay.interval-ms=200"})
@Testcontainers
class OutboxIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;
    @Autowired ConsumerFactory<String, String> consumerFactory;

    private Account funded(String amount) {
        Account a = ledger.openAccount("outbox-" + UUID.randomUUID(), "USD");
        ledger.transfer(new TransferRequest("ob-dep-" + UUID.randomUUID(), SystemAccounts.HOUSE_USD, a.id(),
                new BigDecimal(amount), "funding"));
        return a;
    }

    private long eventsFor(UUID transactionId) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id")
                .param("id", transactionId).query(Long.class).single();
    }

    @Test
    void transfer_writesExactlyOneEvent_andReplayWritesNone() {
        Account a = funded("100.00");
        Account b = ledger.openAccount("outbox-b-" + UUID.randomUUID(), "USD");
        var req = new TransferRequest("ob-" + UUID.randomUUID(), a.id(), b.id(), new BigDecimal("40.00"), "rent");

        UUID txId = ledger.transfer(req).transactionId();
        ledger.transfer(req);

        assertEquals(1, eventsFor(txId));
    }

    @Test
    void failedTransfer_leavesNoEvent() {
        Account poor = ledger.openAccount("outbox-poor-" + UUID.randomUUID(), "USD");
        Account other = ledger.openAccount("outbox-other-" + UUID.randomUUID(), "USD");
        long before = jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single();

        assertThrows(InsufficientFundsException.class, () -> ledger.transfer(new TransferRequest(
                "ob-fail-" + UUID.randomUUID(), poor.id(), other.id(), new BigDecimal("5.00"), "too much")));

        long after = jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single();
        assertEquals(before, after);
    }

    @Test
    void relay_publishesToKafka_withEventIdHeader_andMarksRowPublished() {
        Account a = funded("50.00");
        UUID txId = ledger.transfer(new TransferRequest("ob-flow-" + UUID.randomUUID(), SystemAccounts.HOUSE_USD,
                a.id(), new BigDecimal("7.00"), "flow")).transactionId();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Boolean published = jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = :id")
                    .param("id", txId).query(Boolean.class).single();
            assertTrue(published, "relay should have marked the event published");
        });

        try (Consumer<String, String> consumer = consumerFactory.createConsumer("outbox-test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of("ledger.transfers"));
            ConsumerRecord<String, String> found = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (txId.toString().equals(r.key())) {
                        found = r;
                    }
                }
            }
            assertNotNull(found, "the event should be readable from the topic");
            assertNotNull(found.headers().lastHeader("event-id"));
            assertTrue(found.value().contains(txId.toString()));
        }
    }
}
