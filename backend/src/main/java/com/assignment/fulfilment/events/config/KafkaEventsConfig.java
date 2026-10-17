package com.assignment.fulfilment.events.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topics and listener error handling.
 *
 * <p>Retry ownership: the consumer records every failed attempt in {@code event_failures} and decides
 * when an event is dead-lettered (after {@code app.events.max-retries} attempts). While an event is still
 * retryable the consumer re-throws, and this error handler re-seeks the record so Kafka redelivers it after
 * a fixed back-off. The handler's own attempt budget is larger than max-retries, so in normal operation the
 * consumer's dead-letter decision is reached first; the recoverer below is only a last-resort safety net.
 */
@Configuration
@ConditionalOnClass(NewTopic.class)
public class KafkaEventsConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventsConfig.class);

    public static final String ORDER_EVENTS = "order-events";
    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String INVENTORY_EVENTS = "inventory-events";
    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(INVENTORY_EVENTS).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic orderEventsDltTopic() {
        return TopicBuilder.name(ORDER_EVENTS + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDltTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    CommonErrorHandler kafkaListenerErrorHandler(
            @Value("${app.events.retry-backoff-ms:2000}") long backoffMs,
            @Value("${app.events.max-retries:3}") int maxRetries
    ) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, ex) -> log.error(
                        "Kafka error handler exhausted redelivery for topic={} partition={} offset={}; record skipped",
                        record.topic(), record.partition(), record.offset(), ex),
                new FixedBackOff(backoffMs, maxRetries + 2L)
        );
        return handler;
    }
}
