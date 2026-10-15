package com.portfolio.ledger.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Example consumer: "sends a notification" for every completed transfer. It is the seed of the notification
 * service that moves into its own module in Phase 3b. Idempotent: a redelivered event is recognised by its
 * event-id and skipped.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", havingValue = "true")
class TransferEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferEventConsumer.class);
    static final String CONSUMER = "transfer-notifications";

    private final OutboxRepository store;

    TransferEventConsumer(OutboxRepository store) {
        this.store = store;
    }

    @KafkaListener(topics = "${ledger.outbox.topic:ledger.transfers}", groupId = "${ledger.outbox.consumer-group:ledger-notifications}")
    void onTransfer(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(OutboxRelay.EVENT_ID_HEADER);
        if (header == null) {
            log.warn("Ignoring event without {} header at offset {}", OutboxRelay.EVENT_ID_HEADER, record.offset());
            return;
        }
        UUID eventId = UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        if (!store.firstTimeSeen(eventId, CONSUMER)) {
            log.info("Duplicate event {} ignored", eventId);
            return;
        }
        log.info("NOTIFY: transfer event {} -> {}", eventId, record.value());
    }
}
