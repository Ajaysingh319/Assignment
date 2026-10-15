package com.assignment.fulfilment.events.publisher;

import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asynchronously publishes unpublished outbox events to Kafka.
 *
 * <p>CRASH SCENARIO & DUPLICATE DELIVERY:
 * Distributed systems cannot guarantee end-to-end exactly-once delivery across
 * independent state boundaries (PostgreSQL vs Apache Kafka).
 * 1. Publisher sends event E1 to Kafka.
 * 2. Kafka accepts and writes E1 to the commit log, returning an ACK.
 * 3. Before the publisher can execute "UPDATE outbox_events SET published_at = now()",
 *    the application process crashes, the database connection drops, or power fails.
 * 4. Upon restart/recovery, the publisher queries outbox_events where published_at IS NULL.
 * 5. Event E1 is re-read and published to Kafka a second time.
 *
 * Therefore, consumers MUST be idempotent and utilize durable deduplication
 * via the `processed_events` table.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.events.outbox-poll-interval-ms:2000}", initialDelay = 1000)
    public int publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : pending) {
            boolean success = publishSingleEvent(event);
            if (success) {
                publishedCount++;
            }
        }
        return publishedCount;
    }

    @Transactional
    public boolean publishSingleEvent(OutboxEvent event) {
        String topic = determineTopic(event.getAggregateType());
        try {
            EventEnvelope envelope = new EventEnvelope(
                    event.getId().toString(),
                    event.getEventType(),
                    event.getEventVersion(),
                    event.getCreatedAt(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getCorrelationId(),
                    event.getPayload()
            );

            String messageJson = objectMapper.writeValueAsString(envelope);

            if (kafkaTemplate != null) {
                // Key = aggregateId (guarantees per-aggregate ordering in Kafka partitions)
                kafkaTemplate.send(topic, event.getAggregateId(), messageJson)
                        .get(5, TimeUnit.SECONDS);
            } else {
                log.debug("KafkaTemplate not configured; simulating publishing for event: {}", event.getId());
            }

            event.markPublished();
            outboxEventRepository.save(event);
            return true;
        } catch (Exception e) {
            log.error("Failed to publish outbox event {} to topic {}", event.getId(), topic, e);
            event.recordAttempt(e.getMessage());
            outboxEventRepository.save(event);
            return false;
        }
    }

    public static String determineTopic(String aggregateType) {
        if (aggregateType == null) {
            return "domain-events";
        }
        return switch (aggregateType.toUpperCase()) {
            case "ORDER" -> "order-events";
            case "INVENTORY" -> "inventory-events";
            case "PAYMENT" -> "payment-events";
            default -> "domain-events";
        };
    }
}
