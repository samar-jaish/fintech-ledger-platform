package com.portfolio.notification;

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
    CommonErrorHandler errorHandler(KafkaOperations<String, String> kafka,
                                    @Value("${notification.retry.interval-ms}") long intervalMs,
                                    @Value("${notification.retry.attempts}") long attempts) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (ConsumerRecord<?, ?> record, Exception e) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, attempts));
    }
}
