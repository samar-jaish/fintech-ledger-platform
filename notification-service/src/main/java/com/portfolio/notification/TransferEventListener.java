package com.portfolio.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns each transfer event into a notification. Idempotent: the event-id header identifies the event, and a
 * redelivered one is skipped. A message that cannot be parsed throws, is retried a few times by the error handler
 * (see KafkaErrorConfig), then goes to the dead-letter topic so it cannot block the messages behind it.
 */
@Component
class TransferEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventListener.class);
    static final String EVENT_ID_HEADER = "event-id";

    private final NotificationRepository notifications;
    private final ObjectMapper json = new ObjectMapper();

    TransferEventListener(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = "${notification.topic}")
    void onTransfer(ConsumerRecord<String, String> record) throws Exception {
        var header = record.headers().lastHeader(EVENT_ID_HEADER);
        if (header == null) {
            throw new IllegalArgumentException("Missing " + EVENT_ID_HEADER + " header at offset " + record.offset());
        }
        UUID eventId = UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        TransferCompletedEvent event = json.readValue(record.value(), TransferCompletedEvent.class);

        String message = "Transfer of " + event.amount().toPlainString() + " from " + event.fromAccountId()
                + " to " + event.toAccountId() + (event.description() == null ? "" : " (" + event.description() + ")");
        if (notifications.saveIfNew(eventId, event, message)) {
            log.info("NOTIFY: {}", message);
        } else {
            log.info("Duplicate event {} ignored", eventId);
        }
    }
}
