package com.assignment.fulfilment.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.events.consumer.ConsumerFaultInjector;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.domain.OutboxEvent;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real PostgreSQL + Kafka. Unlike {@link KafkaOutboxEndToEndIntegrationTest}, nothing here calls the publisher:
 * the scheduled outbox poller must publish on its own, and the Kafka listener must consume, retry and
 * dead-letter through the real broker.
 */
@SpringBootTest
@ActiveProfiles("postgres-kafka-test")
@EnabledIfEnvironmentVariable(named = "DATABASE_PASSWORD", matches = ".+",
        disabledReason = "Requires live PostgreSQL and Kafka; set DATABASE_PASSWORD to run")
class KafkaAutomaticPipelineIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private EventFailureRepository eventFailureRepository;

    @Autowired
    private ConsumerFaultInjector faultInjector;

    @Autowired
    private OrderEventConsumer orderEventConsumer;

    @AfterEach
    void disarm() {
        faultInjector.disarm();
    }

    private OrderResponse createOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-AUTO-" + suffix, "Automatic Pipeline Product", null, true));
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-AUTO-" + suffix, "Automatic Pipeline WH", null, true));
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 5L));
        return orderService.createOrder("auto-pipeline-" + suffix,
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(product.id(), 1L, BigDecimal.TEN))));
    }

    private OutboxEvent outboxEvent(UUID orderId, String type) {
        return outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER", orderId.toString())
                .stream().filter(e -> type.equals(e.getEventType())).findFirst()
                .orElseThrow(() -> new AssertionError(type + " not found in outbox"));
    }

    private static <T> T await(Duration timeout, Supplier<Optional<T>> probe, String description) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for: " + description);
    }

    @Test
    @DisplayName("Scheduled poller publishes ORDER_CREATED to Kafka and the listener consumes it, with no manual trigger")
    void orderEventIsPublishedAndConsumedAutomatically() throws Exception {
        OrderResponse order = createOrder();
        UUID eventId = outboxEvent(order.id(), "ORDER_CREATED").getId();

        OutboxEvent published = await(Duration.ofSeconds(20),
                () -> outboxEventRepository.findById(eventId).filter(e -> e.getPublishedAt() != null),
                "outbox event published by the scheduler");
        assertThat(published.getPublishedVia()).isEqualTo(OutboxEvent.VIA_KAFKA);
        assertThat(published.getLastError()).isNull();

        await(Duration.ofSeconds(20),
                () -> processedEventRepository.existsByIdConsumerNameAndIdEventId(orderEventConsumer.getConsumerName(), eventId.toString())
                        ? Optional.of(true) : Optional.empty(),
                "event consumed and recorded in processed_events");
    }

    @Test
    @DisplayName("Transient worker failures are retried through Kafka redelivery and the event recovers")
    void transientFailureIsRetriedAndRecovers() throws Exception {
        OrderResponse order = createOrder();
        faultInjector.arm(2, "ORDER_CANCELLED", order.id().toString());
        orderService.cancelOrder(order.id());
        String eventId = outboxEvent(order.id(), "ORDER_CANCELLED").getId().toString();

        await(Duration.ofSeconds(40),
                () -> processedEventRepository.existsByIdConsumerNameAndIdEventId(orderEventConsumer.getConsumerName(), eventId)
                        ? Optional.of(true) : Optional.empty(),
                "event processed after redelivery");

        EventFailure failure = eventFailureRepository.findByEventIdAndConsumerName(eventId, orderEventConsumer.getConsumerName())
                .orElseThrow(() -> new AssertionError("failed attempts were not recorded"));
        assertThat(failure.getAttemptCount()).isEqualTo(2);
        assertThat(failure.getStatus()).isEqualTo(EventFailureStatus.RESOLVED);
        assertThat(failure.getLastError()).contains("Simulated worker failure");
    }

    @Test
    @DisplayName("Persistent worker failure is dead-lettered after max retries and never marked processed")
    void persistentFailureIsDeadLettered() throws Exception {
        OrderResponse order = createOrder();
        faultInjector.arm(10, "ORDER_CANCELLED", order.id().toString());
        orderService.cancelOrder(order.id());
        String eventId = outboxEvent(order.id(), "ORDER_CANCELLED").getId().toString();

        EventFailure failure = await(Duration.ofSeconds(40),
                () -> eventFailureRepository.findByEventIdAndConsumerName(eventId, orderEventConsumer.getConsumerName())
                        .filter(f -> f.getStatus() == EventFailureStatus.DEAD_LETTERED),
                "event dead-lettered");
        assertThat(failure.getAttemptCount()).isEqualTo(3);
        assertThat(failure.getTopic()).isEqualTo("order-events");

        // Give the container time to (incorrectly) redeliver; the dead-lettered event must stay unprocessed
        Thread.sleep(2000);
        assertThat(processedEventRepository.existsByIdConsumerNameAndIdEventId(orderEventConsumer.getConsumerName(), eventId)).isFalse();
        assertThat(eventFailureRepository.findById(failure.getId()).orElseThrow().getAttemptCount()).isEqualTo(3);
    }
}
