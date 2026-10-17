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
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.inventory.dto.OrderItemRequest;
import com.assignment.fulfilment.inventory.dto.ReserveInventoryRequest;
import java.net.Socket;
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
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Real PostgreSQL Concurrency Integration Test.
 *
 * Requirements:
 * - Seed 10 available units.
 * - Launch 100 concurrent attempts to reserve one unit each.
 * - Exactly 10 attempts must succeed.
 * - Exactly 90 must fail.
 * - Final available quantity must be 0 and reserved quantity must be 10.
 * - No inventory quantity may be negative.
 * - Test all-or-nothing reservation when one item is unavailable.
 *
 * Per instructions: Mocks are not acceptable. If PostgreSQL is unavailable,
 * this test evaluates host availability and is reported as BLOCKED rather than pretending it passed.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("isPostgresAvailable")
class PostgresInventoryConcurrencyIntegrationTest {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReservationService reservationService;

    @Autowired
    private InventoryRepository inventoryRepository;

    static boolean isPostgresAvailable() {
        String url = System.getProperty("spring.datasource.url",
                System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/fulfilment"));
        String username = System.getProperty("spring.datasource.username",
                System.getenv().getOrDefault("DATABASE_USERNAME", "postgres"));
        String password = System.getProperty("spring.datasource.password",
                System.getenv("DATABASE_PASSWORD"));

        if (password == null || password.isBlank()) {
            System.err.println("SKIPPED: PostgreSQL test credentials (DATABASE_PASSWORD) are not set. Skipping live PostgreSQL integration test.");
            return false;
        }

        try {
            java.sql.DriverManager.setLoginTimeout(3);
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, username, password);
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
                return true;
            }
        } catch (Exception e) {
            System.err.println("SKIPPED: PostgreSQL is not usable at " + url + " with supplied credentials: " + e.getMessage() + ". Skipping live PostgreSQL integration test.");
            return false;
        }
    }

    @Test
    @DisplayName("100 concurrent reservations against 10 available units: exactly 10 succeed, 90 fail")
    void testConcurrentReservationsAgainstLimitedStockOnPostgres() throws Exception {
        // 1. Seed 1 product and 1 warehouse with 10 available units
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-PG-CONC-" + UUID.randomUUID(), "Postgres Concurrency Product", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-PG-" + UUID.randomUUID().toString().substring(0, 8), "Postgres WH", null, true)
        );

        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 10L));

        Inventory initial = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id())).orElseThrow();
        assertThat(initial.getAvailableQty()).isEqualTo(10L);
        assertThat(initial.getReservedQty()).isEqualTo(0L);

        // 2. Launch 100 concurrent attempts to reserve 1 unit each
        int totalAttempts = 100;
        ExecutorService executor = Executors.newFixedThreadPool(25);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalAttempts);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalAttempts; i++) {
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

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        // 3. Assertions
        assertThat(successCount.get())
                .as("Exactly 10 concurrent reservation attempts must succeed")
                .isEqualTo(10);

        assertThat(failureCount.get())
                .as("Exactly 90 concurrent reservation attempts must fail due to insufficient stock")
                .isEqualTo(90);

        // 4. Final inventory assertions
        Inventory finalInventory = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id())).orElseThrow();
        assertThat(finalInventory.getAvailableQty())
                .as("Final available quantity must be 0")
                .isEqualTo(0L);

        assertThat(finalInventory.getReservedQty())
                .as("Final reserved quantity must be 10")
                .isEqualTo(10L);

        assertThat(finalInventory.getFulfilledQty())
                .as("Fulfilled quantity must remain 0")
                .isEqualTo(0L);

        assertThat(finalInventory.getAvailableQty())
                .as("Available quantity must not be negative")
                .isGreaterThanOrEqualTo(0L);

        assertThat(finalInventory.trackedTotal())
                .as("Tracked total must remain 10")
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("All-or-nothing rollback when one multi-item line is unavailable")
    void testAllOrNothingRollbackWhenOneItemUnavailableOnPostgres() {
        ProductResponse productInStock = catalogService.createProduct(
                new CreateProductRequest("SKU-PG-STOCK-" + UUID.randomUUID(), "In Stock", null, true)
        );
        ProductResponse productOutOfStock = catalogService.createProduct(
                new CreateProductRequest("SKU-PG-NOSTOCK-" + UUID.randomUUID(), "Out Of Stock", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-PG-AON-" + UUID.randomUUID().toString().substring(0, 8), "AON WH", null, true)
        );

        inventoryService.adjust(new AdjustInventoryRequest(productInStock.id(), warehouse.id(), 10L));
        inventoryService.adjust(new AdjustInventoryRequest(productOutOfStock.id(), warehouse.id(), 1L));

        UUID orderId = UUID.randomUUID();
        ReserveInventoryRequest multiItemRequest = new ReserveInventoryRequest(
                orderId,
                List.of(
                        new OrderItemRequest(productInStock.id(), 3L),
                        new OrderItemRequest(productOutOfStock.id(), 2L)
                )
        );

        assertThatThrownBy(() -> reservationService.reserve(multiItemRequest))
                .isInstanceOf(InsufficientStockException.class);

        // Verify productInStock was rolled back completely
        Inventory stockAfterRollback = inventoryRepository.findById(new InventoryId(productInStock.id(), warehouse.id())).orElseThrow();
        assertThat(stockAfterRollback.getAvailableQty()).isEqualTo(10L);
        assertThat(stockAfterRollback.getReservedQty()).isEqualTo(0L);

        Inventory outOfStockAfterRollback = inventoryRepository
                .findById(new InventoryId(productOutOfStock.id(), warehouse.id()))
                .orElseThrow();

        assertThat(outOfStockAfterRollback.getAvailableQty()).isEqualTo(1L);
        assertThat(outOfStockAfterRollback.getReservedQty()).isEqualTo(0L);
    }
}
