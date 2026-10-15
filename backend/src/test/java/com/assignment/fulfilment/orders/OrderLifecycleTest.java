package com.assignment.fulfilment.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.exception.IdempotencyConflictException;
import com.assignment.fulfilment.common.exception.InsufficientStockException;
import com.assignment.fulfilment.common.exception.InvalidOrderStateTransitionException;
import com.assignment.fulfilment.inventory.InventoryRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderLifecycleTest {

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

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Create order: reserves inventory, enforces idempotency, same key + same payload returns existing")
    void testOrderCreationAndIdempotency() {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-1-" + u, "Order Product 1", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-1-" + u, "Order Warehouse 1", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 10L));

        String key = "idemp-key-" + UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                "USD",
                List.of(new CreateOrderItemRequest(product.id(), 3L, new BigDecimal("25.00")))
        );

        OrderResponse order1 = orderService.createOrder(key, request);
        assertThat(order1.id()).isNotNull();
        assertThat(order1.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order1.totalAmount()).isEqualByComparingTo("75.00");
        assertThat(order1.reservations()).hasSize(1);
        assertThat(order1.reservations().get(0).quantity()).isEqualTo(3L);

        // Verify inventory state: available=7, reserved=3
        Inventory inv = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id())).orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(7L);
        assertThat(inv.getReservedQty()).isEqualTo(3L);

        // Same idempotency key + same payload -> returns original order without re-reserving stock
        OrderResponse order2 = orderService.createOrder(key, request);
        assertThat(order2.id()).isEqualTo(order1.id());
        assertThat(order2.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        inv = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id())).orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(7L);
        assertThat(inv.getReservedQty()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Create order: same key + different payload throws IdempotencyConflictException (409)")
    void testSameKeyDifferentPayloadConflict() {
        String u = uid();
        ProductResponse p1 = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-CONF-1-" + u, "Conflict 1", null, true)
        );
        ProductResponse p2 = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-CONF-2-" + u, "Conflict 2", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-CONF-" + u, "Conflict WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(p1.id(), wh.id(), 10L));
        inventoryService.adjust(new AdjustInventoryRequest(p2.id(), wh.id(), 10L));

        String key = "idemp-conflict-" + UUID.randomUUID();
        CreateOrderRequest request1 = new CreateOrderRequest(
                "USD",
                List.of(new CreateOrderItemRequest(p1.id(), 2L, new BigDecimal("10.00")))
        );
        orderService.createOrder(key, request1);

        // Different quantity
        CreateOrderRequest request2 = new CreateOrderRequest(
                "USD",
                List.of(new CreateOrderItemRequest(p1.id(), 5L, new BigDecimal("10.00")))
        );
        assertThatThrownBy(() -> orderService.createOrder(key, request2))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used with a different request payload");

        // Different product
        CreateOrderRequest request3 = new CreateOrderRequest(
                "USD",
                List.of(new CreateOrderItemRequest(p2.id(), 2L, new BigDecimal("10.00")))
        );
        assertThatThrownBy(() -> orderService.createOrder(key, request3))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Insufficient stock: order creation rolls back, leaves no order and no reservation")
    void testInsufficientStockLeavesNoOrder() {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-OOS-" + u, "Out of Stock", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-OOS-" + u, "OOS Warehouse", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 2L));

        String key = "idemp-oos-" + UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                "USD",
                List.of(new CreateOrderItemRequest(product.id(), 5L, new BigDecimal("10.00")))
        );

        assertThatThrownBy(() -> orderService.createOrder(key, request))
                .isInstanceOf(InsufficientStockException.class);

        // Verify no order exists with that idempotency key
        assertThat(orderRepository.findByIdempotencyKey(key)).isEmpty();

        // Stock remains untouched: available=2, reserved=0
        Inventory inv = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(2L);
        assertThat(inv.getReservedQty()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Valid transitions: PENDING_PAYMENT -> CONFIRMED -> FULFILLED")
    void testValidLifecycleConfirmAndFulfil() {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-FLOW-1-" + u, "Flow 1", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-FLOW-1-" + u, "Flow WH 1", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        OrderResponse order = orderService.createOrder(
                "idemp-flow-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(product.id(), 4L, BigDecimal.TEN)))
        );
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        // Confirm
        OrderResponse confirmed = orderService.confirmOrder(order.id());
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.confirmedAt()).isNotNull();

        // Fulfil
        OrderResponse fulfilled = orderService.fulfilOrder(order.id());
        assertThat(fulfilled.status()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(fulfilled.fulfilledAt()).isNotNull();

        // Stock moved: available=6, reserved=0, fulfilled=4
        Inventory inv = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(6L);
        assertThat(inv.getReservedQty()).isEqualTo(0L);
        assertThat(inv.getFulfilledQty()).isEqualTo(4L);
        assertThat(inv.trackedTotal()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Valid transition: PENDING_PAYMENT -> CANCELLED releases reservations")
    void testValidLifecycleCancelReleasesStock() {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-CAN-1-" + u, "Cancel 1", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-CAN-1-" + u, "Cancel WH 1", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        OrderResponse order = orderService.createOrder(
                "idemp-can-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(product.id(), 4L, BigDecimal.TEN)))
        );

        // Before cancel: available=6, reserved=4
        Inventory invBefore = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(invBefore.getAvailableQty()).isEqualTo(6L);
        assertThat(invBefore.getReservedQty()).isEqualTo(4L);

        OrderResponse cancelled = orderService.cancelOrder(order.id());
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();

        // After cancel: available=10, reserved=0
        Inventory invAfter = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(invAfter.getAvailableQty()).isEqualTo(10L);
        assertThat(invAfter.getReservedQty()).isEqualTo(0L);
        assertThat(invAfter.trackedTotal()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Reject invalid transitions")
    void testRejectInvalidTransitions() {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-INV-" + u, "Invalid Transition Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-INV-" + u, "Invalid Transition WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        OrderResponse order = orderService.createOrder(
                "idemp-inv-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(product.id(), 2L, BigDecimal.TEN)))
        );

        // Cannot fulfil PENDING_PAYMENT order
        assertThatThrownBy(() -> orderService.fulfilOrder(order.id()))
                .isInstanceOf(InvalidOrderStateTransitionException.class)
                .hasMessageContaining("Allowed transition is CONFIRMED -> FULFILLED");

        // Cancel order -> CANCELLED
        orderService.cancelOrder(order.id());

        // Cannot confirm CANCELLED order
        assertThatThrownBy(() -> orderService.confirmOrder(order.id()))
                .isInstanceOf(InvalidOrderStateTransitionException.class)
                .hasMessageContaining("Allowed transition is PENDING_PAYMENT -> CONFIRMED");

        // Cannot fulfil CANCELLED order
        assertThatThrownBy(() -> orderService.fulfilOrder(order.id()))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }

    @Test
    @DisplayName("Repeated transitions are safe and idempotent")
    void testRepeatedTransitionsAreSafe() {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-REP-" + u, "Repeated Transitions", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-REP-" + u, "Repeated WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        OrderResponse order = orderService.createOrder(
                "idemp-rep-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(product.id(), 3L, BigDecimal.TEN)))
        );

        // Cancel once
        OrderResponse c1 = orderService.cancelOrder(order.id());
        assertThat(c1.status()).isEqualTo(OrderStatus.CANCELLED);

        // Cancel second time: safe, no extra release, same status
        OrderResponse c2 = orderService.cancelOrder(order.id());
        assertThat(c2.status()).isEqualTo(OrderStatus.CANCELLED);

        Inventory inv = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(10L);
        assertThat(inv.getReservedQty()).isEqualTo(0L);

        // Test repeated confirmation & fulfilment on another order
        OrderResponse order2 = orderService.createOrder(
                "idemp-rep2-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(product.id(), 3L, BigDecimal.TEN)))
        );
        OrderResponse conf1 = orderService.confirmOrder(order2.id());
        OrderResponse conf2 = orderService.confirmOrder(order2.id());
        assertThat(conf1.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(conf2.status()).isEqualTo(OrderStatus.CONFIRMED);

        OrderResponse ful1 = orderService.fulfilOrder(order2.id());
        OrderResponse ful2 = orderService.fulfilOrder(order2.id());
        assertThat(ful1.status()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(ful2.status()).isEqualTo(OrderStatus.FULFILLED);

        Inventory inv2 = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(inv2.getFulfilledQty()).isEqualTo(3L);
        assertThat(inv2.getReservedQty()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Order expiry transitions PENDING_PAYMENT to CANCELLED and releases reservations")
    void testOrderExpiry() {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-EXP-" + u, "Expiry Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-EXP-" + u, "Expiry WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        OrderResponse order = orderService.createOrder(
                "idemp-exp-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(product.id(), 5L, BigDecimal.TEN)))
        );

        OrderResponse expired = orderService.expireOrder(order.id());
        assertThat(expired.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(expired.cancelledAt()).isNotNull();

        // Inventory restored
        Inventory inv = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(10L);
        assertThat(inv.getReservedQty()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Concurrent requests with the same idempotency key create exactly 1 order and reserve stock once")
    void testConcurrentOrderCreationSameKey() throws Exception {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-CONC-" + u, "Concurrent Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-CONC-" + u, "Concurrent WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        String key = "idemp-concurrent-" + UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                "USD",
                List.of(new CreateOrderItemRequest(product.id(), 2L, BigDecimal.TEN))
        );

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<UUID> firstOrderId = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    OrderResponse response = orderService.createOrder(key, request);
                    if (response != null && response.id() != null) {
                        successCount.incrementAndGet();
                        firstOrderId.compareAndSet(null, response.id());
                        assertThat(response.id()).isEqualTo(firstOrderId.get());
                    }
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // All concurrent threads resolved to the same order
        assertThat(successCount.get()).isEqualTo(threads);

        // Exactly one order exists in repository
        assertThat(orderRepository.findAll().stream().filter(o -> o.getIdempotencyKey().equals(key)).count())
                .isEqualTo(1);

        // Stock reserved exactly once (2 units), available=8, reserved=2
        Inventory inv = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(8L);
        assertThat(inv.getReservedQty()).isEqualTo(2L);
    }
}
