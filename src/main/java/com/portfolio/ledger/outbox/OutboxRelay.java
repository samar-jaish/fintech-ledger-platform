package com.portfolio.ledger.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka. Runs only when ledger.outbox.relay.enabled=true.
 *
 * Each pass claims a batch inside one transaction, sends each event, and marks it published after Kafka
 * acknowledges it. If the process dies between "Kafka acknowledged" and "marked published", the event is sent
 * again on the next pass: delivery is at-least-once, so consumers must ignore duplicates (see event-id header).
 * On the first failure the pass stops, so events are never sent out of order within a batch.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", havingValue = "true")
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final String EVENT_ID_HEADER = "event-id";
    static final String EVENT_TYPE_HEADER = "event-type";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final String topic;
    private final int batchSize;

    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                @Value("${ledger.outbox.topic:ledger.transfers}") String topic,
                @Value("${ledger.outbox.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay.interval-ms:500}")
    void publishPending() {
        try {
            Integer sent = tx.execute(status -> publishBatch());
            if (sent != null && sent > 0) {
                log.debug("Outbox relay published {} event(s)", sent);
            }
        } catch (Exception e) {
            // Nothing is lost: unpublished rows stay in the table and are retried on the next pass.
            log.warn("Outbox relay pass failed, will retry: {}", e.toString());
        }
    }

    private int publishBatch() {
        List<OutboxEvent> events = outbox.claimUnpublished(batchSize);
        int sent = 0;
        for (OutboxEvent e : events) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, e.aggregateId().toString(), e.payload());
            record.headers().add(EVENT_ID_HEADER, e.id().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EVENT_TYPE_HEADER, e.eventType().getBytes(StandardCharsets.UTF_8));
            try {
                kafka.send(record).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("Kafka send failed for outbox event {}: {}", e.id(), ex.toString());
                break;
            }
            outbox.markPublished(e.id());
            sent++;
        }
        return sent;
    }
}
