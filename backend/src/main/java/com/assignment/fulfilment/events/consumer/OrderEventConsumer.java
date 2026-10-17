package com.assignment.fulfilment.events.consumer;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Fulfilment worker consuming order and payment events.
 *
 * <p>Each event is applied at most once per consumer (processed_events). A failed attempt is recorded in
 * event_failures and re-thrown so the listener container redelivers it after a back-off; once
 * {@code app.events.max-retries} attempts have failed the event is dead-lettered to {@code <topic>.DLT}
 * and the offset is committed so the partition is not blocked.
 */
@Component
public class OrderEventConsumer {

    public static final String CONSUMER_NAME = "order-fulfilment-consumer";
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final IdempotentEventConsumer idempotentEventConsumer;
    private final EventRetryHandler eventRetryHandler;
    private final ConsumerFaultInjector faultInjector;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final String consumerName;

    public OrderEventConsumer(
            IdempotentEventConsumer idempotentEventConsumer,
            EventRetryHandler eventRetryHandler,
            ConsumerFaultInjector faultInjector,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${app.events.consumer-name:" + CONSUMER_NAME + "}") String consumerName
    ) {
        this.idempotentEventConsumer = idempotentEventConsumer;
        this.eventRetryHandler = eventRetryHandler;
        this.faultInjector = faultInjector;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.consumerName = consumerName;
    }

    @KafkaListener(
            topics = {"order-events", "payment-events"},
            groupId = "${spring.kafka.consumer.group-id:fulfilment-order-group}",
            autoStartup = "${app.kafka.consumer.auto-startup:true}"
    )
    public void onMessage(
            @Payload String message,
            @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset
    ) {
        String resolvedTopic = topic != null ? topic : "order-events";
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventId().isBlank()) {
                throw new IllegalArgumentException("Event envelope has no eventId");
            }
        } catch (Exception e) {
            eventRetryHandler.recordPoisonMessage(resolvedTopic, partition, offset, consumerName, message,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        IdempotentEventConsumer.Outcome outcome = consume(envelope, resolvedTopic, partition, offset);
        if (outcome == IdempotentEventConsumer.Outcome.FAILED_WILL_RETRY) {
            // Re-throw so the container's error handler re-seeks and Kafka redelivers this record.
            throw new RetryableEventException("Event " + envelope.eventId() + " failed and will be redelivered");
        }
    }

    public boolean consumeEnvelope(EventEnvelope envelope, String topic, Integer partition, Long offset) {
        return consume(envelope, topic, partition, offset) == IdempotentEventConsumer.Outcome.PROCESSED;
    }

    private IdempotentEventConsumer.Outcome consume(EventEnvelope envelope, String topic, Integer partition, Long offset) {
        return idempotentEventConsumer.process(
                consumerName,
                envelope,
                topic,
                partition,
                offset,
                this::applyBusinessEffects
        );
    }

    private void applyBusinessEffects(EventEnvelope envelope) throws Exception {
        log.info("Processing {} event: id={}, type={}, aggregateId={}",
                envelope.aggregateType(), envelope.eventId(), envelope.eventType(), envelope.aggregateId());

        faultInjector.maybeFail(envelope);

        // Audit the processed event atomically with the processed-event marker
        auditLogRepository.save(new AuditLog(
                "consumer_event",
                envelope.aggregateId(),
                "CONSUMED_" + envelope.eventType(),
                consumerName,
                "eventId=" + envelope.eventId() + ", version=" + envelope.eventVersion()
        ));

        processedCount.incrementAndGet();
    }

    public String getConsumerName() {
        return consumerName;
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public static class RetryableEventException extends RuntimeException {
        public RetryableEventException(String message) {
            super(message);
        }
    }
}
