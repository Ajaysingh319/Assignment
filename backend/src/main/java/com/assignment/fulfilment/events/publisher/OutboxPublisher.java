package com.assignment.fulfilment.events.publisher;

import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asynchronously publishes unpublished outbox events to Kafka.
 *
 * <p>An event is marked published ({@code published_at}, {@code published_via = KAFKA}) only after the
 * broker acknowledged the send. A failed or timed-out send increments {@code attempt_count}, stores
 * {@code last_error}, and leaves the event pending so the next poll retries it.
 *
 * <p>Without a KafkaTemplate (H2 unit-test profile) events stay pending, unless the explicit test-only
 * property {@code app.events.publisher.simulate-without-kafka=true} is set; simulated publications are
 * stamped {@code published_via = SIMULATED} so they can never be confused with real ones.
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
    private final boolean simulateWithoutKafka;
    private final long sendTimeoutMs;
    private final AtomicBoolean missingKafkaWarned = new AtomicBoolean(false);

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.events.publisher.simulate-without-kafka:false}") boolean simulateWithoutKafka,
            @Value("${app.events.publisher.send-timeout-ms:5000}") long sendTimeoutMs
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.simulateWithoutKafka = simulateWithoutKafka;
        this.sendTimeoutMs = sendTimeoutMs;
        if (kafkaTemplate != null) {
            log.info("OutboxPublisher initialised with KafkaTemplate; pending events will be published to Kafka");
        } else if (simulateWithoutKafka) {
            log.warn("OutboxPublisher running in TEST-ONLY simulation mode: events are marked published_via=SIMULATED and NOT sent to Kafka");
        } else {
            log.warn("OutboxPublisher has no KafkaTemplate; outbox events will remain pending until Kafka is configured");
        }
    }

    /**
     * Polls pending outbox events in creation order. Synchronized so the scheduled poll and the manual
     * "publish now" endpoint cannot publish the same batch concurrently inside one JVM.
     */
    @Scheduled(fixedDelayString = "${app.events.outbox-poll-interval-ms:2000}", initialDelay = 1000)
    public synchronized int publishPendingEvents() {
        if (kafkaTemplate == null && !simulateWithoutKafka) {
            if (missingKafkaWarned.compareAndSet(false, true)) {
                log.warn("Skipping outbox publication: KafkaTemplate is not available");
            }
            return 0;
        }

        List<OutboxEvent> pending = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : pending) {
            if (publishSingleEvent(event)) {
                publishedCount++;
            }
        }
        log.info("Outbox poll: {} of {} pending events published", publishedCount, pending.size());
        return publishedCount;
    }

    /**
     * Publishes one event and records the outcome on the outbox row. Each repository save runs in its own
     * transaction, so the outcome is committed independently of any other event in the batch.
     */
    public boolean publishSingleEvent(OutboxEvent event) {
        String topic = determineTopic(event.getAggregateType());
        try {
            if (kafkaTemplate != null) {
                SendResult<String, String> result = send(topic, event);
                event.markPublished(OutboxEvent.VIA_KAFKA);
                outboxEventRepository.save(event);
                log.info("Published outbox event id={} type={} aggregateId={} to {}-{}@{}",
                        event.getId(), event.getEventType(), event.getAggregateId(), topic,
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                return true;
            }
            if (simulateWithoutKafka) {
                log.warn("TEST-ONLY simulation: marking outbox event {} as published without sending to Kafka", event.getId());
                event.markPublished(OutboxEvent.VIA_SIMULATED);
                outboxEventRepository.save(event);
                return true;
            }
            throw new IllegalStateException("KafkaTemplate is not available");
        } catch (Exception e) {
            String reason = describe(e);
            log.error("Failed to publish outbox event id={} type={} to topic {} (attempt {}): {}",
                    event.getId(), event.getEventType(), topic, event.getAttemptCount() + 1, reason, e);
            event.recordAttempt(reason);
            outboxEventRepository.save(event);
            return false;
        }
    }

    /**
     * Sends an already-published event again without changing its outbox state. Used to demonstrate that
     * duplicate delivery is absorbed by the idempotent consumer.
     */
    public void republish(OutboxEvent event) throws Exception {
        if (kafkaTemplate == null) {
            throw new IllegalStateException("KafkaTemplate is not available; cannot re-deliver event " + event.getId());
        }
        send(determineTopic(event.getAggregateType()), event);
        log.info("Re-delivered outbox event id={} type={} (duplicate delivery demonstration)", event.getId(), event.getEventType());
    }

    public boolean isKafkaAvailable() {
        return kafkaTemplate != null;
    }

    private SendResult<String, String> send(String topic, OutboxEvent event)
            throws JsonProcessingException, InterruptedException, ExecutionException, TimeoutException {
        String messageJson = objectMapper.writeValueAsString(toEnvelope(event));
        // Key = aggregateId (guarantees per-aggregate ordering in Kafka partitions)
        return kafkaTemplate.send(topic, event.getAggregateId(), messageJson).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private static EventEnvelope toEnvelope(OutboxEvent event) {
        return new EventEnvelope(
                event.getId().toString(),
                event.getEventType(),
                event.getEventVersion(),
                event.getCreatedAt(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getCorrelationId(),
                event.getPayload()
        );
    }

    private static String describe(Throwable e) {
        Throwable root = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (root instanceof TimeoutException) {
            return "Timed out waiting for Kafka acknowledgement";
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
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
