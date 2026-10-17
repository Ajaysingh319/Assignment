package com.assignment.fulfilment.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.common.exception.IdempotencyConflictException;
import com.assignment.fulfilment.common.exception.InsufficientStockException;
import com.assignment.fulfilment.inventory.InventoryRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.ReservationRepository;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import com.assignment.fulfilment.inventory.dto.InitializeInventoryRequest;
import com.assignment.fulfilment.orders.domain.OrderRequestOutcome;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderRequestResponse;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRequestOutcomeTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private record Stock(UUID productId, UUID warehouseId) {
    }

    private Stock stock(long units) {
        String u = UUID.randomUUID().toString().substring(0, 8);
        ProductResponse p = catalogService.createProduct(new CreateProductRequest("SKU-REQ-" + u, "Request Product", null, true));
        WarehouseResponse w = catalogService.createWarehouse(new CreateWarehouseRequest("WH-REQ-" + u, "Request WH", null, true));
        inventoryService.initialize(new InitializeInventoryRequest(p.id(), w.id(), units));
        return new Stock(p.id(), w.id());
    }

    private static CreateOrderRequest oneOf(UUID productId) {
        return new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(productId, 1L, new BigDecimal("5.00"))));
    }

    private Inventory inventory(Stock s) {
        return inventoryRepository.findById(new InventoryId(s.productId(), s.warehouseId())).orElseThrow();
    }

    @Test
    @DisplayName("Every request outcome is persisted: accepted, rejected for stock, replayed, conflicting")
    void outcomesAreRecorded() {
        Stock s = stock(2);
        String prefix = "req-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        OrderResponse first = orderService.createOrder(prefix + "1", oneOf(s.productId()));
        orderService.createOrder(prefix + "2", oneOf(s.productId()));
        assertThatThrownBy(() -> orderService.createOrder(prefix + "3", oneOf(s.productId())))
                .isInstanceOf(InsufficientStockException.class);

        // Retrying request 1 with the same key and payload returns the same order and creates nothing
        OrderResponse replay = orderService.createOrder(prefix + "1", oneOf(s.productId()));
        assertThat(replay.id()).isEqualTo(first.id());

        // Same key, different payload
        assertThatThrownBy(() -> orderService.createOrder(prefix + "1",
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(s.productId(), 2L, new BigDecimal("5.00"))))))
                .isInstanceOf(IdempotencyConflictException.class);

        Map<String, Long> counts = orderService.requestOutcomeCounts(prefix);
        assertThat(counts.get("ACCEPTED")).isEqualTo(2L);
        assertThat(counts.get("REJECTED_INSUFFICIENT_STOCK")).isEqualTo(1L);
        assertThat(counts.get("IDEMPOTENT_REPLAY")).isEqualTo(1L);
        assertThat(counts.get("REJECTED_IDEMPOTENCY_CONFLICT")).isEqualTo(1L);
        assertThat(counts.get("TOTAL")).isEqualTo(5L);

        // Only 2 real orders exist; the rejected key never became an order
        assertThat(orderRepository.findByIdempotencyKey(prefix + "3")).isEmpty();
        assertThat(inventory(s).getAvailableQty()).isZero();
        assertThat(inventory(s).getReservedQty()).isEqualTo(2L);

        List<OrderRequestResponse> rejected = orderService.listRequests(OrderRequestOutcome.REJECTED_INSUFFICIENT_STOCK, prefix, 10);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).orderId()).isNull();
        assertThat(rejected.get(0).reason()).contains("Insufficient stock");
    }

    @Test
    @DisplayName("Multi-product order: one short line rejects the whole order and reserves nothing")
    void multiProductOrderIsAllOrNothing() {
        Stock plenty = stock(5);
        Stock empty = stock(0);
        String key = "multi-" + UUID.randomUUID();

        assertThatThrownBy(() -> orderService.createOrder(key, new CreateOrderRequest("USD", List.of(
                new CreateOrderItemRequest(plenty.productId(), 2L, new BigDecimal("5.00")),
                new CreateOrderItemRequest(empty.productId(), 1L, new BigDecimal("5.00"))))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(inventory(plenty).getAvailableQty()).isEqualTo(5L);
        assertThat(inventory(plenty).getReservedQty()).isZero();
        assertThat(reservationRepository.findByProductId(plenty.productId())).isEmpty();
        assertThat(orderRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    @DisplayName("Multi-product order then cancellation: every line's reservation is released exactly once")
    void multiProductCancellationReleasesAllLines() {
        Stock a = stock(4);
        Stock b = stock(3);
        OrderResponse order = orderService.createOrder("multi-cancel-" + UUID.randomUUID(), new CreateOrderRequest("USD", List.of(
                new CreateOrderItemRequest(a.productId(), 2L, new BigDecimal("5.00")),
                new CreateOrderItemRequest(b.productId(), 3L, new BigDecimal("7.00")))));

        assertThat(order.totalAmount()).isEqualByComparingTo("31.00");
        assertThat(inventory(a).getReservedQty()).isEqualTo(2L);
        assertThat(inventory(b).getAvailableQty()).isZero();

        orderService.cancelOrder(order.id());
        orderService.cancelOrder(order.id()); // repeated cancel must not double-release

        assertThat(inventory(a).getAvailableQty()).isEqualTo(4L);
        assertThat(inventory(a).getReservedQty()).isZero();
        assertThat(inventory(b).getAvailableQty()).isEqualTo(3L);
        assertThat(inventory(b).getReservedQty()).isZero();
        assertThat(reservationRepository.findByOrderId(order.id()))
                .hasSize(2)
                .allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("Expiry scheduler cancels stale PENDING_PAYMENT orders and releases their stock")
    void staleOrdersExpire() {
        Stock s = stock(3);
        OrderResponse order = orderService.createOrder("expire-" + UUID.randomUUID(), oneOf(s.productId()));
        assertThat(inventory(s).getReservedQty()).isEqualTo(1L);

        // Negative expiry window: every pending order is considered stale
        new OrderExpiryScheduler(orderService, -1).expireStaleOrders();

        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventory(s).getAvailableQty()).isEqualTo(3L);
        assertThat(inventory(s).getReservedQty()).isZero();
        assertThat(auditLogRepository.findByEntityTypeAndEntityId("order", order.id().toString()))
                .anyMatch(a -> a.getAction().equals("ORDER_EXPIRED"));
    }
}
