package com.portfolio.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure policy for the listener: retry a failing message a few times, then publish it to "<topic>.DLT" (dead-letter
 * topic) and move on. Without this, one bad message would be retried forever and block everything behind it.
 * Spring Boot picks up a CommonErrorHandler bean and applies it to the listener container automatically.
 */
@Configuration
class KafkaErrorConfig {

    @Bean
    CommonErrorHandler errorHandler(KafkaOperations<String, String> kafka, MeterRegistry registry,
                                    @Value("${notification.retry.interval-ms}") long intervalMs,
                                    @Value("${notification.retry.attempts}") long attempts) {
        // Counts every message given up on, so a dashboard or alert shows poison messages without anyone reading logs.
        Counter deadLettered = Counter.builder("notification.dead.lettered")
                .description("Messages sent to the dead-letter topic after the retries were used up").register(registry);
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (ConsumerRecord<?, ?> record, Exception e) -> new TopicPartition(record.topic() + ".DLT", record.partition())) {
            @Override
            public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
                deadLettered.increment();
                super.accept(record, consumer, exception);
            }
        };
        return new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, attempts));
    }
}
