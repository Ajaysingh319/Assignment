
package com.assignment.fulfilment.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.publisher.OutboxPublisher;
import com.assignment.fulfilment.events.repository.OutboxEventRepository;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("postgres-kafka-test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "DATABASE_PASSWORD", matches = ".+",
        disabledReason = "Requires live PostgreSQL and Kafka; set DATABASE_PASSWORD to run")
class KafkaOutboxEndToEndIntegrationTest {

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
    private OutboxPublisher outboxPublisher;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Real PostgreSQL + Kafka: order outbox event is published and consumed")
    void orderOutboxEventIsPublishedToKafkaAndConsumed() throws InterruptedException {

        // Verify that Spring created a real KafkaTemplate bean.
        assertThat(kafkaTemplate).isNotNull();

        String suffix = UUID.randomUUID().toString();

        // 1. Create test product and warehouse.
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest(
                        "SKU-KAFKA-" + suffix,
                        "Kafka Integration Product",
                        null,
                        true
                )
        );

        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest(
                        "WH-KAFKA-" + suffix,
                        "Kafka Integration Warehouse",
                        null,
                        true
                )
        );

        // 2. Add stock and create an order.
        inventoryService.adjust(
                new AdjustInventoryRequest(product.id(), warehouse.id(), 10L)
        );

        OrderResponse order = orderService.createOrder(
                "kafka-e2e-" + suffix,
                new CreateOrderRequest(
                        "USD",
                        List.of(
                                new CreateOrderItemRequest(
                                        product.id(), 1L, BigDecimal.TEN
                                )
                        )
                )
        );

        // 3. Find the ORDER_CREATED event in PostgreSQL's outbox.
        List<OutboxEvent> events =
                outboxEventRepository
                        .findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                                "ORDER", order.id().toString()
                        );

        OutboxEvent createdEvent = events.stream()
                .filter(event -> "ORDER_CREATED".equals(event.getEventType()))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("ORDER_CREATED event was not found in the outbox"));

        // 4. Publish through the application publisher.
        // The scheduled publisher may already have published this event.
        if (createdEvent.getPublishedAt() == null) {
            boolean published = outboxPublisher.publishSingleEvent(createdEvent);
            assertThat(published)
                    .as("OutboxPublisher should successfully publish using Kafka")
                    .isTrue();
        }

        OutboxEvent reloadedEvent = outboxEventRepository
                .findById(createdEvent.getId())
                .orElseThrow();

        assertThat(reloadedEvent.getPublishedAt())
                .as("The outbox event should be marked published after Kafka acknowledgement")
                .isNotNull();

        // 5. Wait for the application's Kafka listener to process the event.
        String eventId = createdEvent.getId().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        boolean processed = false;

        while (System.nanoTime() < deadline) {
            processed = processedEventRepository
                    .existsByIdConsumerNameAndIdEventId(
                            OrderEventConsumer.CONSUMER_NAME,
                            eventId
                    );

            if (processed) {
                break;
            }

            Thread.sleep(250);
        }

        assertThat(processed)
                .as("Kafka consumer should persist processed_events marker for event %s",
                        eventId)
                .isTrue();
    }
}
