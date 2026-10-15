package com.assignment.fulfilment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.exception.InsufficientStockException;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.inventory.dto.OrderItemRequest;
import com.assignment.fulfilment.inventory.dto.ReservationResponse;
import com.assignment.fulfilment.inventory.dto.ReserveInventoryRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class InventoryReservationEngineTest {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReservationService reservationService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    @DisplayName("Reserve multi-line order across multiple warehouses using deterministic ordering")
    void testMultiProductMultiWarehouseReservation() {
        ProductResponse p1 = catalogService.createProduct(
                new CreateProductRequest("SKU-MULTI-1", "Product 1", null, true)
        );
        ProductResponse p2 = catalogService.createProduct(
                new CreateProductRequest("SKU-MULTI-2", "Product 2", null, true)
        );

        WarehouseResponse wh1 = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-1", "Warehouse A", null, true)
        );
        WarehouseResponse wh2 = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-2", "Warehouse B", null, true)
        );

        // Ensure wh1 and wh2 are ordered deterministically by UUID
        WarehouseResponse firstWh = wh1.id().compareTo(wh2.id()) < 0 ? wh1 : wh2;
        WarehouseResponse secondWh = wh1.id().compareTo(wh2.id()) < 0 ? wh2 : wh1;

        // P1 has 3 in firstWh and 5 in secondWh
        inventoryService.adjust(new AdjustInventoryRequest(p1.id(), firstWh.id(), 3L));
        inventoryService.adjust(new AdjustInventoryRequest(p1.id(), secondWh.id(), 5L));

        // P2 has 10 in firstWh
        inventoryService.adjust(new AdjustInventoryRequest(p2.id(), firstWh.id(), 10L));

        UUID orderId = UUID.randomUUID();
        // Request 6 of P1 (requires spanning firstWh:3 and secondWh:3) and 4 of P2
        ReserveInventoryRequest request = new ReserveInventoryRequest(
                orderId,
                List.of(
                        new OrderItemRequest(p1.id(), 6L),
                        new OrderItemRequest(p2.id(), 4L)
                )
        );

        ReservationResponse response = reservationService.reserve(request);
        assertThat(response.status()).isEqualTo("RESERVED");
        assertThat(response.items()).hasSize(3); // 2 allocations for P1 (3 from firstWh, 3 from secondWh) + 1 for P2

        // Verify P1 in firstWh: available=0, reserved=3
        Inventory p1FirstWh = inventoryRepository.findById(new InventoryId(p1.id(), firstWh.id())).orElseThrow();
        assertThat(p1FirstWh.getAvailableQty()).isEqualTo(0L);
        assertThat(p1FirstWh.getReservedQty()).isEqualTo(3L);
        assertThat(p1FirstWh.trackedTotal()).isEqualTo(3L);

        // Verify P1 in secondWh: available=2, reserved=3
        Inventory p1SecondWh = inventoryRepository.findById(new InventoryId(p1.id(), secondWh.id())).orElseThrow();
        assertThat(p1SecondWh.getAvailableQty()).isEqualTo(2L);
        assertThat(p1SecondWh.getReservedQty()).isEqualTo(3L);
        assertThat(p1SecondWh.trackedTotal()).isEqualTo(5L);

        // Verify P2 in firstWh: available=6, reserved=4
        Inventory p2FirstWh = inventoryRepository.findById(new InventoryId(p2.id(), firstWh.id())).orElseThrow();
        assertThat(p2FirstWh.getAvailableQty()).isEqualTo(6L);
        assertThat(p2FirstWh.getReservedQty()).isEqualTo(4L);
        assertThat(p2FirstWh.trackedTotal()).isEqualTo(10L);
    }

    @Test
    @DisplayName("All-or-nothing: if any item cannot be fully reserved, roll back every reservation for that order")
    void testAllOrNothingRollbackWhenOneItemCannotBeReserved() {
        ProductResponse p1 = catalogService.createProduct(
                new CreateProductRequest("SKU-AON-1", "Product AON 1", null, true)
        );
        ProductResponse p2 = catalogService.createProduct(
                new CreateProductRequest("SKU-AON-2", "Product AON 2", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-AON-1", "Warehouse AON", null, true)
        );

        // P1 has 10 units, P2 has only 1 unit
        inventoryService.adjust(new AdjustInventoryRequest(p1.id(), wh.id(), 10L));
        inventoryService.adjust(new AdjustInventoryRequest(p2.id(), wh.id(), 1L));

        UUID orderId = UUID.randomUUID();
        // Request 5 of P1 (which would succeed on its own) and 2 of P2 (which exceeds 1)
        ReserveInventoryRequest request = new ReserveInventoryRequest(
                orderId,
                List.of(
                        new OrderItemRequest(p1.id(), 5L),
                        new OrderItemRequest(p2.id(), 2L)
                )
        );

        assertThatThrownBy(() -> reservationService.reserve(request))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock for product " + p2.id());

        // Assert that P1 stock was NOT modified (rolled back completely)
        Inventory p1Inv = inventoryRepository.findById(new InventoryId(p1.id(), wh.id())).orElseThrow();
        assertThat(p1Inv.getAvailableQty()).as("P1 available stock must remain 10").isEqualTo(10L);
        assertThat(p1Inv.getReservedQty()).as("P1 reserved stock must remain 0").isEqualTo(0L);

        // Assert that P2 stock was NOT modified
        Inventory p2Inv = inventoryRepository.findById(new InventoryId(p2.id(), wh.id())).orElseThrow();
        assertThat(p2Inv.getAvailableQty()).as("P2 available stock must remain 1").isEqualTo(1L);
        assertThat(p2Inv.getReservedQty()).as("P2 reserved stock must remain 0").isEqualTo(0L);

        // Assert that zero reservations exist for this order
        assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    @DisplayName("Cancellation releases each reservation at most once")
    void testCancellationReleasesAtMostOnce() {
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-CANC-1", "Product Cancel", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-CANC-1", "Warehouse Cancel", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(new ReserveInventoryRequest(
                orderId,
                List.of(new OrderItemRequest(product.id(), 4L))
        ));

        Inventory afterReserve = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(afterReserve.getAvailableQty()).isEqualTo(6L);
        assertThat(afterReserve.getReservedQty()).isEqualTo(4L);

        // First cancellation releases reservations
        ReservationResponse cancelResponse1 = reservationService.cancel(orderId);
        assertThat(cancelResponse1.status()).isEqualTo("RELEASED");

        Inventory afterCancel1 = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(afterCancel1.getAvailableQty()).isEqualTo(10L);
        assertThat(afterCancel1.getReservedQty()).isEqualTo(0L);
        assertThat(afterCancel1.trackedTotal()).isEqualTo(10L);

        // Second cancellation must NOT release stock again (at most once!)
        ReservationResponse cancelResponse2 = reservationService.cancel(orderId);
        assertThat(cancelResponse2.status()).isEqualTo("RELEASED");

        Inventory afterCancel2 = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(afterCancel2.getAvailableQty()).as("Stock must not be released again").isEqualTo(10L);
        assertThat(afterCancel2.getReservedQty()).isEqualTo(0L);
        assertThat(afterCancel2.trackedTotal()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Fulfilment moves reserved quantities to fulfilled quantities at most once")
    void testFulfilmentMovesReservedToFulfilledAtMostOnce() {
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-FULF-1", "Product Fulfil", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-FULF-1", "Warehouse Fulfil", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), wh.id(), 10L));

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(new ReserveInventoryRequest(
                orderId,
                List.of(new OrderItemRequest(product.id(), 7L))
        ));

        Inventory afterReserve = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(afterReserve.getAvailableQty()).isEqualTo(3L);
        assertThat(afterReserve.getReservedQty()).isEqualTo(7L);
        assertThat(afterReserve.getFulfilledQty()).isEqualTo(0L);

        // First fulfilment moves reserved to fulfilled
        ReservationResponse fulfilResponse1 = reservationService.fulfil(orderId);
        assertThat(fulfilResponse1.status()).isEqualTo("FULFILLED");

        Inventory afterFulfil1 = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(afterFulfil1.getAvailableQty()).isEqualTo(3L);
        assertThat(afterFulfil1.getReservedQty()).isEqualTo(0L);
        assertThat(afterFulfil1.getFulfilledQty()).isEqualTo(7L);
        assertThat(afterFulfil1.trackedTotal()).isEqualTo(10L);

        // Second fulfilment must NOT move stock again (at most once!)
        ReservationResponse fulfilResponse2 = reservationService.fulfil(orderId);
        assertThat(fulfilResponse2.status()).isEqualTo("FULFILLED");

        Inventory afterFulfil2 = inventoryRepository.findById(new InventoryId(product.id(), wh.id())).orElseThrow();
        assertThat(afterFulfil2.getAvailableQty()).isEqualTo(3L);
        assertThat(afterFulfil2.getReservedQty()).isEqualTo(0L);
        assertThat(afterFulfil2.getFulfilledQty()).as("Fulfilled quantity must not increase again").isEqualTo(7L);
        assertThat(afterFulfil2.trackedTotal()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Concurrent reservation stress test: 100 concurrent requests against 10 stock: exactly 10 succeed, 90 fail")
    void testConcurrentReservationsAgainstLimitedStock() throws Exception {
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-CONC-H2-" + UUID.randomUUID(), "H2 Concurrency Product", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-CONC-H2-" + UUID.randomUUID().toString().substring(0, 8), "H2 WH", null, true)
        );

        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 10L));

        int totalThreads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(25);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            UUID orderId = UUID.randomUUID();
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    reservationService.reserve(new ReserveInventoryRequest(
                            orderId,
                            List.of(new OrderItemRequest(product.id(), 1L))
                    ));
                    successCount.incrementAndGet();
                } catch (InsufficientStockException exception) {
                    failureCount.incrementAndGet();
                } catch (Exception exception) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        assertThat(successCount.get())
                .as("Exactly 10 attempts must succeed")
                .isEqualTo(10);

        assertThat(failureCount.get())
                .as("Exactly 90 attempts must fail")
                .isEqualTo(90);

        Inventory finalInventory = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id())).orElseThrow();
        assertThat(finalInventory.getAvailableQty())
                .as("Final available quantity must be 0")
                .isEqualTo(0L);

        assertThat(finalInventory.getReservedQty())
                .as("Final reserved quantity must be 10")
                .isEqualTo(10L);

        assertThat(finalInventory.getFulfilledQty())
                .as("Final fulfilled quantity must be 0")
                .isEqualTo(0L);

        assertThat(finalInventory.getAvailableQty())
                .as("Available quantity must be nonnegative")
                .isGreaterThanOrEqualTo(0L);

        assertThat(finalInventory.trackedTotal())
                .as("Tracked total must remain 10")
                .isEqualTo(10L);
    }
}
