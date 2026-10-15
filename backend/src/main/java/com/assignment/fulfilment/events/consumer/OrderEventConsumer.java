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

@Component
public class OrderEventConsumer {

    public static final String CONSUMER_NAME = "order-fulfilment-consumer";
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final IdempotentEventConsumer idempotentEventConsumer;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final AtomicInteger processedCount = new AtomicInteger(0);

    public OrderEventConsumer(
            IdempotentEventConsumer idempotentEventConsumer,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper
    ) {
        this.idempotentEventConsumer = idempotentEventConsumer;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "order-events",
            groupId = "${spring.kafka.consumer.group-id:fulfilment-order-group}",
            autoStartup = "${app.kafka.consumer.auto-startup:true}"
    )
    public void onMessage(
            @Payload String message,
            @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset
    ) {
        try {
            EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
            consumeEnvelope(envelope, topic != null ? topic : "order-events", partition, offset);
        } catch (Exception e) {
            log.error("Failed to parse event message: {}", message, e);
        }
    }

    public boolean consumeEnvelope(EventEnvelope envelope, String topic, Integer partition, Long offset) {
        return idempotentEventConsumer.processIdempotently(
                CONSUMER_NAME,
                envelope,
                topic,
                partition,
                offset,
                this::applyBusinessEffects
        );
    }

    private void applyBusinessEffects(EventEnvelope envelope) throws Exception {
        log.info("Processing order event: id={}, type={}, aggregateId={}",
                envelope.eventId(), envelope.eventType(), envelope.aggregateId());

        processedCount.incrementAndGet();

        // Audit the processed event atomically with consumer effects
        auditLogRepository.save(new AuditLog(
                "consumer_event",
                envelope.aggregateId(),
                "CONSUMED_" + envelope.eventType(),
                CONSUMER_NAME,
                "eventId=" + envelope.eventId() + ", version=" + envelope.eventVersion()
        ));
    }

    public int getProcessedCount() {
        return processedCount.get();
    }
}
