package com.assignment.fulfilment.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.consumer.EventRetryHandler;
import com.assignment.fulfilment.events.consumer.IdempotentEventConsumer;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.dto.EventFailureResponse;
import com.assignment.fulfilment.events.dto.EventFailureSummaryResponse;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FailedEventWorkflowTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRetryHandler eventRetryHandler;

    @Autowired
    private EventFailureRepository eventFailureRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OrderEventConsumer orderEventConsumer;

    @Autowired
    private IdempotentEventConsumer idempotentEventConsumer;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createEnvelopeJson(String eventId, String aggregateId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ORDER_CONFIRMED\",\"eventVersion\":1,\"occurredAt\":\""
                + Instant.now() + "\",\"aggregateType\":\"ORDER\",\"aggregateId\":\"" + aggregateId + "\",\"payload\":\"{}\"}";
    }

    @Test
    @DisplayName("Retry exhaustion: bounded retries route exhausted event to DEAD_LETTERED with full history")
    void testRetryExhaustion() {
        String eventId = "evt-exh-" + uid();
        String consumer = "consumer-exhaustion-" + uid();
        String payload = createEnvelopeJson(eventId, UUID.randomUUID().toString());

        // Attempt 1: RETRYING
        EventFailure f1 = eventRetryHandler.handleFailure(eventId, "order-events", 0, 10L, consumer, "DB connection timeout", payload);
        assertThat(f1.getStatus()).isEqualTo(EventFailureStatus.RETRYING);
        assertThat(f1.getAttemptCount()).isEqualTo(1);
        Instant firstFailedAt = f1.getFirstFailedAt();
        assertThat(firstFailedAt).isNotNull();

        // Attempt 2: RETRYING
        EventFailure f2 = eventRetryHandler.handleFailure(eventId, "order-events", 0, 11L, consumer, "DB connection timeout 2", payload);
        assertThat(f2.getStatus()).isEqualTo(EventFailureStatus.RETRYING);
        assertThat(f2.getAttemptCount()).isEqualTo(2);
        assertThat(f2.getFirstFailedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(firstFailedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

        // Attempt 3: Exhausted -> DEAD_LETTERED
        EventFailure f3 = eventRetryHandler.handleFailure(eventId, "order-events", 0, 12L, consumer, "Fatal error max retry reached", payload);
        assertThat(f3.getStatus()).isEqualTo(EventFailureStatus.DEAD_LETTERED);
        assertThat(f3.getAttemptCount()).isEqualTo(3);
        assertThat(f3.getFirstFailedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(firstFailedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(f3.getLastFailedAt()).isAfterOrEqualTo(firstFailedAt);

        // Visible in dead-letter queries
        List<EventFailureResponse> deadLettered = eventService.listFailures(EventFailureStatus.DEAD_LETTERED);
        assertThat(deadLettered.stream().anyMatch(d -> d.eventId().equals(eventId))).isTrue();
    }

    @Test
    @DisplayName("Manual retry: audited, reprocesses event with original ID, and resolves without deleting failure record")
    void testManualRetryAndResolution() {
        String eventId = "evt-retry-" + uid();
        String aggId = UUID.randomUUID().toString();
        String payload = createEnvelopeJson(eventId, aggId);

        EventFailure failure = eventRetryHandler.handleFailure(
                eventId,
                "order-events",
                0,
                50L,
                OrderEventConsumer.CONSUMER_NAME,
                "Simulated initial failure",
                payload
        );
        failure.recordRetry("Fatal simulated exception", 1);
        failure = eventFailureRepository.save(failure);
        assertThat(failure.getStatus()).isEqualTo(EventFailureStatus.DEAD_LETTERED);

        int initialConsumerProcessed = orderEventConsumer.getProcessedCount();

        // Trigger manual retry
        EventFailureResponse response = eventService.retryFailure(failure.getId());
        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.resolvedAt()).isNotNull();
        assertThat(response.attemptCount()).isEqualTo(failure.getAttemptCount());
        assertThat(response.firstFailedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(failure.getFirstFailedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(response.eligibleForRetry()).isFalse();

        // Check consumer processed count increased exactly once
        assertThat(orderEventConsumer.getProcessedCount()).isEqualTo(initialConsumerProcessed + 1);

        // Check failure history is NOT deleted
        EventFailure persisted = eventFailureRepository.findById(failure.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(EventFailureStatus.RESOLVED);
        assertThat(persisted.getLastError()).isEqualTo("Fatal simulated exception");
        assertThat(persisted.getResolvedAt()).isNotNull();

        // Check audit history
        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityId("event_failure", failure.getId().toString());
        assertThat(auditLogs).hasSizeGreaterThanOrEqualTo(2);
        assertThat(auditLogs.stream().anyMatch(a -> a.getAction().equals("EVENT_RETRY_INITIATED"))).isTrue();
        assertThat(auditLogs.stream().anyMatch(a -> a.getAction().equals("EVENT_FAILURE_RESOLVED"))).isTrue();
    }

    @Test
    @DisplayName("Duplicate replay & idempotency: repeated retries do not re-execute consumer effects")
    void testDuplicateReplayIdempotency() {
        String eventId = "evt-dup-" + uid();
        String aggId = UUID.randomUUID().toString();
        String payload = createEnvelopeJson(eventId, aggId);

        EventFailure failure = eventRetryHandler.handleFailure(
                eventId,
                "order-events",
                0,
                80L,
                OrderEventConsumer.CONSUMER_NAME,
                "Initial network error",
                payload
        );
        failure.recordRetry("Initial network error", 1);
        failure = eventFailureRepository.save(failure);

        int initialConsumerProcessed = orderEventConsumer.getProcessedCount();

        // First retry: reprocesses and marks RESOLVED
        EventFailureResponse firstRetry = eventService.retryFailure(failure.getId());
        assertThat(firstRetry.status()).isEqualTo("RESOLVED");
        assertThat(orderEventConsumer.getProcessedCount()).isEqualTo(initialConsumerProcessed + 1);

        // Second retry (duplicate operator replay): safe and idempotent
        EventFailureResponse secondRetry = eventService.retryFailure(failure.getId());
        assertThat(secondRetry.status()).isEqualTo("RESOLVED");
        // Consumer count MUST NOT increase again!
        assertThat(orderEventConsumer.getProcessedCount()).isEqualTo(initialConsumerProcessed + 1);

        // Processed event marker exists exactly once
        assertThat(processedEventRepository.existsByIdConsumerNameAndIdEventId(
                OrderEventConsumer.CONSUMER_NAME, eventId
        )).isTrue();
    }

    @Test
    @DisplayName("Duplicate Kafka delivery: redelivery of consumed event is skipped safely")
    void testDuplicateKafkaDeliverySkipped() {
        String eventId = "evt-kafka-dup-" + uid();
        EventEnvelope envelope = new EventEnvelope(
                eventId, "ORDER_CREATED", 1, Instant.now(), "ORDER", UUID.randomUUID().toString(), null, "{}"
        );
        String consumer = "consumer-kafka-test-" + uid();
        AtomicInteger runCount = new AtomicInteger(0);

        IdempotentEventConsumer.ConsumerAction action = env -> runCount.incrementAndGet();

        // 1st delivery
        boolean res1 = idempotentEventConsumer.processIdempotently(consumer, envelope, "order-events", 0, 100L, action);
        assertThat(res1).isTrue();
        assertThat(runCount.get()).isEqualTo(1);

        // 2nd delivery (Kafka redelivery / duplicate)
        boolean res2 = idempotentEventConsumer.processIdempotently(consumer, envelope, "order-events", 0, 101L, action);
        assertThat(res2).isFalse();
        assertThat(runCount.get()).isEqualTo(1); // Not executed again
    }

    @Test
    @DisplayName("Dashboard summary: failure metrics and retry-eligible batch processing")
    void testDashboardSummaryAndBatchRetry() {
        String eventId1 = "evt-batch-1-" + uid();
        String eventId2 = "evt-batch-2-" + uid();
        String payload1 = createEnvelopeJson(eventId1, UUID.randomUUID().toString());
        String payload2 = createEnvelopeJson(eventId2, UUID.randomUUID().toString());

        EventFailure f1 = eventRetryHandler.handleFailure(eventId1, "order-events", 0, 201L, OrderEventConsumer.CONSUMER_NAME, "Err1", payload1);
        f1.recordRetry("Exhausted 1", 1);
        eventFailureRepository.save(f1);

        EventFailure f2 = eventRetryHandler.handleFailure(eventId2, "order-events", 0, 202L, OrderEventConsumer.CONSUMER_NAME, "Err2", payload2);
        f2.recordRetry("Exhausted 2", 1);
        eventFailureRepository.save(f2);

        // Check summary
        EventFailureSummaryResponse summary = eventService.getSummary();
        assertThat(summary.totalFailures()).isGreaterThanOrEqualTo(2);
        assertThat(summary.deadLetteredCount()).isGreaterThanOrEqualTo(2);

        // Batch retry eligible failures
        List<EventFailureResponse> retried = eventService.retryEligibleFailures();
        assertThat(retried).isNotEmpty();
        assertThat(retried.stream().anyMatch(r -> r.id().equals(f1.getId()) && "RESOLVED".equals(r.status()))).isTrue();
        assertThat(retried.stream().anyMatch(r -> r.id().equals(f2.getId()) && "RESOLVED".equals(r.status()))).isTrue();
        assertThat(eventService.getFailure(f1.getId()).status()).isEqualTo("RESOLVED");
        assertThat(eventService.getFailure(f2.getId()).status()).isEqualTo("RESOLVED");
    }
}
