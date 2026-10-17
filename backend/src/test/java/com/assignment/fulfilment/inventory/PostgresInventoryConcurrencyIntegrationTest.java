
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

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
        String url = System.getProperty(
                "spring.datasource.url",
                System.getenv().getOrDefault(
                        "DATABASE_URL",
                        "jdbc:postgresql://localhost:5432/fulfilment"
                )
        );

        String username = System.getProperty(
                "spring.datasource.username",
                System.getenv().getOrDefault("DATABASE_USERNAME", "postgres")
        );

        String password = System.getProperty(
                "spring.datasource.password",
                System.getenv("DATABASE_PASSWORD")
        );

        if (password == null || password.isBlank()) {
            System.err.println(
                    "SKIPPED: DATABASE_PASSWORD is not set."
            );
            return false;
        }

        try {
            DriverManager.setLoginTimeout(3);

            try (Connection conn =
                         DriverManager.getConnection(url, username, password);
                 Statement stmt = conn.createStatement()) {

                stmt.execute("SELECT 1");
                return true;
            }
        } catch (Exception e) {
            System.err.println(
                    "SKIPPED: PostgreSQL is unavailable: " + e.getMessage()
            );
            return false;
        }
    }

    @Test
    @DisplayName(
            "Concurrent reservations must never exceed available PostgreSQL stock"
    )
    void testConcurrentReservationsAgainstLimitedStockOnPostgres()
            throws Exception {

        // 1. Read configurable stock and request count.
        long initialStock = Long.getLong("demo.stock", 10L);
        int totalAttempts = Integer.getInteger("demo.requests", 100);

        if (initialStock <= 0 || totalAttempts <= 0) {
            throw new IllegalArgumentException(
                    "Stock and request count must be positive"
            );
        }

        // 2. Create a fresh product and warehouse for this test.
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest(
                        "SKU-PG-CONC-" + UUID.randomUUID(),
                        "Postgres Concurrency Product",
                        null,
                        true
                )
        );

        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest(
                        "WH-PG-" + UUID.randomUUID()
                                .toString().substring(0, 8),
                        "Postgres WH",
                        null,
                        true
                )
        );

        // 3. Initialize inventory in PostgreSQL.
        inventoryService.adjust(
                new AdjustInventoryRequest(
                        product.id(),
                        warehouse.id(),
                        initialStock
                )
        );

        Inventory initial = inventoryRepository.findById(
                new InventoryId(product.id(), warehouse.id())
        ).orElseThrow();

        assertThat(initial.getAvailableQty()).isEqualTo(initialStock);
        assertThat(initial.getReservedQty()).isZero();

        // 4. Prepare concurrent reservation attempts.
        int workerCount = Math.min(totalAttempts, 25);

        ExecutorService executor =
                Executors.newFixedThreadPool(workerCount);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch =
                new CountDownLatch(totalAttempts);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger stockRejectionCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < totalAttempts; i++) {
                UUID orderId = UUID.randomUUID();

                executor.submit(() -> {
                    try {
                        // All workers wait here until the test releases them.
                        startLatch.await();

                        reservationService.reserve(
                                new ReserveInventoryRequest(
                                        orderId,
                                        List.of(
                                                new OrderItemRequest(
                                                        product.id(), 1L
                                                )
                                        )
                                )
                        );

                        successCount.incrementAndGet();

                    } catch (InsufficientStockException e) {
                        // Expected when no stock remains.
                        stockRejectionCount.incrementAndGet();

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        unexpectedErrorCount.incrementAndGet();

                    } catch (Exception e) {
                        // Database errors and other bugs are NOT stock rejections.
                        e.printStackTrace();
                        unexpectedErrorCount.incrementAndGet();

                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // 5. Release all workers and wait for completion.
            startLatch.countDown();

            boolean completed =
                    doneLatch.await(60, TimeUnit.SECONDS);

            assertThat(completed)
                    .as("Every reservation attempt must finish within 60 seconds")
                    .isTrue();

        } finally {
            // Avoid leaving worker threads running if the test fails.
            startLatch.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        // 6. Calculate expected results for the supplied inputs.
        int expectedSuccess =
                (int) Math.min(initialStock, (long) totalAttempts);

        int expectedStockRejections =
                totalAttempts - expectedSuccess;

        System.out.println("========== CONCURRENCY TEST RESULT ==========");
        System.out.println("Initial stock       : " + initialStock);
        System.out.println("Concurrent requests : " + totalAttempts);
        System.out.println("Successful reserves : " + successCount.get());
        System.out.println("Stock rejections    : " + stockRejectionCount.get());
        System.out.println("Unexpected errors   : " + unexpectedErrorCount.get());

        // 7. Verify request results.
        assertThat(unexpectedErrorCount.get())
                .as("No unexpected errors should occur")
                .isZero();

        assertThat(successCount.get())
                .as("Reservations must not exceed initial stock")
                .isEqualTo(expectedSuccess);

        assertThat(stockRejectionCount.get())
                .as("Remaining requests should be rejected for insufficient stock")
                .isEqualTo(expectedStockRejections);

        // 8. Verify actual final PostgreSQL inventory.
        Inventory finalInventory = inventoryRepository.findById(
                new InventoryId(product.id(), warehouse.id())
        ).orElseThrow();

        assertThat(finalInventory.getAvailableQty())
                .isEqualTo(initialStock - expectedSuccess);

        assertThat(finalInventory.getReservedQty())
                .isEqualTo(expectedSuccess);

        assertThat(finalInventory.getFulfilledQty()).isZero();

        assertThat(finalInventory.getAvailableQty())
                .isGreaterThanOrEqualTo(0L);

        assertThat(finalInventory.trackedTotal())
                .isEqualTo(initialStock);

        System.out.println("Final available     : "
                + finalInventory.getAvailableQty());
        System.out.println("Final reserved      : "
                + finalInventory.getReservedQty());
        System.out.println("=============================================");
    }

    @Test
    @DisplayName("All-or-nothing rollback when one multi-item line is unavailable")
    void testAllOrNothingRollbackWhenOneItemUnavailableOnPostgres() {

        ProductResponse productInStock = catalogService.createProduct(
                new CreateProductRequest(
                        "SKU-PG-STOCK-" + UUID.randomUUID(),
                        "In Stock",
                        null,
                        true
                )
        );

        ProductResponse productOutOfStock = catalogService.createProduct(
                new CreateProductRequest(
                        "SKU-PG-NOSTOCK-" + UUID.randomUUID(),
                        "Out Of Stock",
                        null,
                        true
                )
        );

        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest(
                        "WH-PG-AON-" + UUID.randomUUID()
                                .toString().substring(0, 8),
                        "AON WH",
                        null,
                        true
                )
        );

        inventoryService.adjust(
                new AdjustInventoryRequest(
                        productInStock.id(), warehouse.id(), 10L
                )
        );

        inventoryService.adjust(
                new AdjustInventoryRequest(
                        productOutOfStock.id(), warehouse.id(), 1L
                )
        );

        UUID orderId = UUID.randomUUID();

        ReserveInventoryRequest multiItemRequest =
                new ReserveInventoryRequest(
                        orderId,
                        List.of(
                                new OrderItemRequest(
                                        productInStock.id(), 3L
                                ),
                                new OrderItemRequest(
                                        productOutOfStock.id(), 2L
                                )
                        )
                );

        assertThatThrownBy(
                () -> reservationService.reserve(multiItemRequest)
        ).isInstanceOf(InsufficientStockException.class);

        // The first product must remain unchanged after rollback.
        Inventory stockAfterRollback = inventoryRepository.findById(
                new InventoryId(productInStock.id(), warehouse.id())
        ).orElseThrow();

        assertThat(stockAfterRollback.getAvailableQty()).isEqualTo(10L);
        assertThat(stockAfterRollback.getReservedQty()).isZero();

        Inventory outOfStockAfterRollback = inventoryRepository.findById(
                new InventoryId(productOutOfStock.id(), warehouse.id())
        ).orElseThrow();

        assertThat(outOfStockAfterRollback.getAvailableQty()).isEqualTo(1L);
        assertThat(outOfStockAfterRollback.getReservedQty()).isZero();
    }
}
