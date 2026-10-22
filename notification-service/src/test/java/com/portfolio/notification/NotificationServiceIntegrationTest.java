package com.portfolio.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Postgres and Kafka. Retries are shortened so the dead-letter test is quick. */
@SpringBootTest(properties = {"notification.retry.interval-ms=100", "notification.retry.attempts=2"})
@Testcontainers
class NotificationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    private static final String TOPIC = "ledger.transfers";

    @Autowired KafkaTemplate<String, String> template;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired NotificationRepository notifications;
    @Autowired MeterRegistry metrics;

    private void publish(String key, UUID eventId, String json) throws Exception {
        var record = new ProducerRecord<>(TOPIC, key, json);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        template.send(record).get();
    }

    private static String event(UUID tx) {
        // includes an unknown field on purpose: the reader must tolerate additions
        return "{\"transactionId\":\"" + tx + "\",\"fromAccountId\":\"" + UUID.randomUUID()
                + "\",\"toAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":12.50,\"description\":\"lunch\",\"newField\":1}";
    }

    @Test
    void event_becomesOneNotification_evenWhenDeliveredTwice() throws Exception {
        UUID tx = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(tx.toString(), eventId, event(tx));
        publish(tx.toString(), eventId, event(tx));   // duplicate delivery of the same event

        await().atMost(Duration.ofSeconds(60)).until(() -> notifications.countForTransaction(tx) >= 1);
        Thread.sleep(2000);   // give the duplicate time to be processed (and ignored)
        assertEquals(1, notifications.countForTransaction(tx));
        assertTrue(metrics.get("notification.events").tag("result", "duplicate").counter().count() >= 1,
                "the ignored duplicate should be counted");
    }

    @Test
    void eventWithOwners_becomesANotificationThatBothOwnersCanSee() throws Exception {
        UUID tx = UUID.randomUUID();
        String payer = "payer-" + tx, payee = "payee-" + tx;
        publish(tx.toString(), UUID.randomUUID(), "{\"transactionId\":\"" + tx + "\",\"fromAccountId\":\"" + UUID.randomUUID()
                + "\",\"toAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":3.00,\"fromOwner\":\"" + payer
                + "\",\"toOwner\":\"" + payee + "\"}");

        await().atMost(Duration.ofSeconds(60)).until(() -> notifications.countForTransaction(tx) == 1);
        assertEquals("SENT", notifications.latestFor(payer, 10).get(0).viewFor(payer).direction());
        assertEquals("RECEIVED", notifications.latestFor(payee, 10).get(0).viewFor(payee).direction());
    }

    @Test
    void unreadableMessage_endsUpOnTheDeadLetterTopic() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        publish(key, UUID.randomUUID(), "this is not json");

        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            ConsumerRecord<String, String> found = null;
            long deadline = System.currentTimeMillis() + 60_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        found = r;
                    }
                }
            }
            assertNotNull(found, "the bad message should be dead-lettered");
            assertEquals("this is not json", found.value());
            assertTrue(metrics.get("notification.dead.lettered").counter().count() >= 1,
                    "the dead-lettered message should be counted");
        }
    }
}
