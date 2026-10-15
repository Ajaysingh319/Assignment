package com.assignment.fulfilment.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.consumer.EventRetryHandler;
import com.assignment.fulfilment.events.consumer.IdempotentEventConsumer;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.dto.EventFailureResponse;
import com.assignment.fulfilment.events.publisher.OutboxPublisher;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
import com.assignment.fulfilment.events.repository.OutboxEventRepository;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
class EventProcessingTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private EventFailureRepository eventFailureRepository;

    @Autowired
    private IdempotentEventConsumer idempotentEventConsumer;

    @Autowired
    private EventRetryHandler eventRetryHandler;

    @Autowired
    private OrderEventConsumer orderEventConsumer;

    @Autowired
    private EventService eventService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Transactional Outbox: outbox events staged in the same transaction as order creation and confirmation")
    void testOutboxStagedInSameTransaction() {
        String u = uid();
        ProductResponse p = catalogService.createProduct(
                new CreateProductRequest("SKU-EVT-" + u, "Event Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-EVT-" + u, "Event WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(p.id(), wh.id(), 15L));

        // 1. Create order
        String key = "outbox-key-" + UUID.randomUUID();
        OrderResponse order = orderService.createOrder(
                key,
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(p.id(), 2L, BigDecimal.TEN)))
        );

        // Verify outbox_events has ORDER_CREATED
        List<OutboxEvent> orderEvents = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "ORDER", order.id().toString()
        );
        assertThat(orderEvents).isNotEmpty();
        OutboxEvent createdEvt = orderEvents.get(0);
        assertThat(createdEvt.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(createdEvt.getAggregateId()).isEqualTo(order.id().toString());
        assertThat(createdEvt.getPublishedAt()).isNull();

        // 2. Confirm order
        orderService.confirmOrder(order.id());

        // Verify outbox_events has ORDER_CONFIRMED
        List<OutboxEvent> updatedOrderEvents = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "ORDER", order.id().toString()
        );
        assertThat(updatedOrderEvents).hasSize(2);
        assertThat(updatedOrderEvents.get(1).getEventType()).isEqualTo("ORDER_CONFIRMED");

        // 3. Publish pending outbox events asynchronously
        int published = outboxPublisher.publishPendingEvents();
        assertThat(published).isGreaterThanOrEqualTo(2);

        // Verify published_at is stamped
        OutboxEvent reloaded = outboxEventRepository.findById(createdEvt.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Idempotent Consumer: duplicate delivery is detected via processed_events and safely skipped")
    void testDuplicateDeliveryTolerated() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                "ORDER_CREATED",
                1,
                Instant.now(),
                "ORDER",
                UUID.randomUUID().toString(),
                null,
                "{\"orderId\":\"test\"}"
        );

        String consumerName = "test-consumer-duplicate";
        AtomicInteger executions = new AtomicInteger(0);

        IdempotentEventConsumer.ConsumerAction action = env -> executions.incrementAndGet();

        // First delivery: executed and recorded
        boolean firstRun = idempotentEventConsumer.processIdempotently(
                consumerName, envelope, "order-events", 0, 100L, action
        );
        assertThat(firstRun).isTrue();
        assertThat(executions.get()).isEqualTo(1);
        assertThat(processedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, eventId)).isTrue();

        // Second delivery (simulating crash scenario duplicate from Kafka): skipped!
        boolean secondRun = idempotentEventConsumer.processIdempotently(
                consumerName, envelope, "order-events", 0, 101L, action
        );
        assertThat(secondRun).isFalse();
        assertThat(executions.get()).isEqualTo(1); // Not incremented again!
    }

    @Test
    @DisplayName("Consumer Failure Atomicity: exception rolls back consumer effects and records failure in event_failures")
    void testFailureRollbackAndPersistence() {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                "ORDER_CREATED",
                1,
                Instant.now(),
                "ORDER",
                UUID.randomUUID().toString(),
                null,
                "{\"orderId\":\"fail-test\"}"
        );

        String consumerName = "test-consumer-failing";
        AtomicBoolean markerSaved = new AtomicBoolean(false);

        IdempotentEventConsumer.ConsumerAction failingAction = env -> {
            markerSaved.set(true);
            throw new RuntimeException("Simulated transient database exception");
        };

        boolean result = idempotentEventConsumer.processIdempotently(
                consumerName, envelope, "order-events", 0, 200L, failingAction
        );
        assertThat(result).isFalse();

        // Processed event marker must NOT be persisted (atomic rollback)
        assertThat(processedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, eventId)).isFalse();

        // Failure must be persisted in event_failures with status RETRYING
        EventFailure failure = eventFailureRepository.findByEventIdAndConsumerName(eventId, consumerName).orElseThrow();
        assertThat(failure.getAttemptCount()).isEqualTo(1);
        assertThat(failure.getStatus()).isEqualTo(EventFailureStatus.RETRYING);
        assertThat(failure.getLastError()).contains("Simulated transient database exception");
    }

    @Test
    @DisplayName("Retry exhaustion routes event to DEAD_LETTERED status")
    void testRetryExhaustionRoutesToDeadLetter() {
        String eventId = UUID.randomUUID().toString();
        String consumerName = "test-consumer-exhaustion";
        String payload = "{\"data\":\"test\"}";

        // Attempt 1 -> RETRYING (max 3 retries)
        EventFailure f1 = eventRetryHandler.handleFailure(eventId, "order-events", 0, 300L, consumerName, "Error 1", payload);
        assertThat(f1.getStatus()).isEqualTo(EventFailureStatus.RETRYING);
        assertThat(f1.getAttemptCount()).isEqualTo(1);

        // Attempt 2 -> RETRYING
        EventFailure f2 = eventRetryHandler.handleFailure(eventId, "order-events", 0, 301L, consumerName, "Error 2", payload);
        assertThat(f2.getStatus()).isEqualTo(EventFailureStatus.RETRYING);
        assertThat(f2.getAttemptCount()).isEqualTo(2);

        // Attempt 3 -> DEAD_LETTERED (retries exhausted)
        EventFailure f3 = eventRetryHandler.handleFailure(eventId, "order-events", 0, 302L, consumerName, "Error 3", payload);
        assertThat(f3.getStatus()).isEqualTo(EventFailureStatus.DEAD_LETTERED);
        assertThat(f3.getAttemptCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Audited Replay: operator triggers retry, reprocesses with original event ID, and resolves failure")
    void testAuditedReplayMechanism() {
        String eventId = UUID.randomUUID().toString();
        String aggregateId = UUID.randomUUID().toString();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ORDER_CONFIRMED\",\"eventVersion\":1,\"occurredAt\":\"" + Instant.now() + "\",\"aggregateType\":\"ORDER\",\"aggregateId\":\"" + aggregateId + "\",\"payload\":\"{}\"}";

        // Seed dead-lettered failure
        EventFailure failure = eventRetryHandler.handleFailure(
                eventId,
                "order-events",
                0,
                400L,
                OrderEventConsumer.CONSUMER_NAME,
                "Temporary failure",
                payload
        );
        // Force to dead-lettered
        failure.recordRetry("Final failure", 1);
        eventFailureRepository.save(failure);
        assertThat(failure.getStatus()).isEqualTo(EventFailureStatus.DEAD_LETTERED);

        int initialConsumerProcessed = orderEventConsumer.getProcessedCount();

        // Execute audited retry
        EventFailureResponse response = eventService.retryFailure(failure.getId());
        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.resolvedAt()).isNotNull();

        // Consumer processed count increased by 1
        assertThat(orderEventConsumer.getProcessedCount()).isEqualTo(initialConsumerProcessed + 1);

        // Audit log was recorded
        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityId("event_failure", failure.getId().toString());
        assertThat(auditLogs).isNotEmpty();
        assertThat(auditLogs.stream().anyMatch(a -> a.getAction().equals("EVENT_FAILURE_RESOLVED"))).isTrue();
    }
}
