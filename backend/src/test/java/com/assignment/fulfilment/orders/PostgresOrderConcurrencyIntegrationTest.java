package com.assignment.fulfilment.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.inventory.InventoryRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.ReservationRepository;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-End Concurrency Test for Distributed Order Fulfilment and Reconciliation Platform.
 *
 * Objective:
 * Prove that 100 simultaneous purchase requests against inventory containing exactly 10 available units
 * cannot oversell stock.
 *
 * Key Concurrency Mechanics:
 * - Real 100-worker thread pool (Executors.newFixedThreadPool(100)).
 * - Readiness latch (readyLatch) ensuring all 100 workers are in memory and blocked before release.
 * - Start barrier (startBarrier) releasing all 100 workers at the exact same instant.
 * - HTTP 409 body inspection verifying specific 'Insufficient stock' error (distinguishing other conflicts).
 * - Comprehensive reservation audit across all rows matching product and warehouse in PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres-test")
@EnabledIfEnvironmentVariable(named = "DATABASE_PASSWORD", matches = ".+",
        disabledReason = "Requires a live PostgreSQL instance; set DATABASE_PASSWORD to run")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class PostgresOrderConcurrencyIntegrationTest {

    static {
        checkAndPrepareDatabase();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static void checkAndPrepareDatabase() {
        String url = System.getProperty("spring.datasource.url",
                System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/fulfilment"));
        String username = System.getProperty("spring.datasource.username",
                System.getenv().getOrDefault("DATABASE_USERNAME", "postgres"));
        String password = System.getProperty("spring.datasource.password",
                System.getenv("DATABASE_PASSWORD"));

        if (password == null || password.isBlank()) {
            System.err.println("""
                ================================================================================
                                POSTGRES CONCURRENCY TEST BLOCKED
                ================================================================================
                Prerequisite Blocker: Missing PostgreSQL credentials (DATABASE_PASSWORD).
                This end-to-end concurrency test targets a live PostgreSQL database instance
                and strictly disallows H2, mock repositories, or Docker.
                
                Service Status: postgresql-x64-17 is running on localhost:5432.
                Authentication: SCRAM-SHA-256 requires a password for user '%s'.
                
                To unblock and execute the test against PostgreSQL:
                  $env:DATABASE_PASSWORD = "<your-postgres-password>"
                  mvn test -Dtest=PostgresOrderConcurrencyIntegrationTest
                ================================================================================
                """.formatted(username));
            return;
        }

        try {
            String maintenanceUrl = url.replace("/fulfilment", "/postgres");
            DriverManager.setLoginTimeout(3);
            try (Connection conn = DriverManager.getConnection(maintenanceUrl, username, password);
                 Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'fulfilment'");
                if (!rs.next()) {
                    stmt.execute("CREATE DATABASE fulfilment");
                    System.out.println("Created database 'fulfilment' on PostgreSQL instance.");
                }
            }
        } catch (Exception e) {
            System.err.println("PostgreSQL maintenance pre-flight notice: " + e.getMessage());
        }
    }

    record HttpResponseRecord(
            int index,
            String idempotencyKey,
            int statusCode,
            String responseBody,
            UUID orderId,
            String failureReason,
            long latencyMs
    ) {}

    @Test
    @DisplayName("E2E Concurrency: 100 simultaneous purchase requests against 10 available units on PostgreSQL")
    void test100ConcurrentOrdersAgainst10StockOnPostgres() throws Exception {
        long startTime = System.currentTimeMillis();

        // 1. Setup isolated test catalog and warehouse
        String runId = UUID.randomUUID().toString().substring(0, 8);
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-PG-E2E-" + runId, "Postgres Concurrency Test Product " + runId, null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-PG-" + runId, "Postgres Concurrency WH " + runId, null, true)
        );

        // 2. Initialize inventory with exactly 10 available units and 0 reserved units
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 10L));

        Inventory initialInventory = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id()))
                .orElseThrow(() -> new IllegalStateException("Initial inventory record not found"));
        assertThat(initialInventory.getAvailableQty())
                .as("Initial available quantity must be exactly 10")
                .isEqualTo(10L);
        assertThat(initialInventory.getReservedQty())
                .as("Initial reserved quantity must be exactly 0")
                .isEqualTo(0L);

        // 3. Prepare true 100-request concurrency
        int totalRequests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startBarrier = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalRequests);

        ConcurrentLinkedQueue<HttpResponseRecord> responseRecords = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientStockCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
        Set<UUID> successfulOrderIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        List<String> failedIdempotencyKeys = Collections.synchronizedList(new ArrayList<>());

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        String targetUrl = "http://localhost:" + port + "/api/orders";

        for (int i = 0; i < totalRequests; i++) {
            final int requestIndex = i;
            final String idempotencyKey = "conc-order-" + runId + "-" + requestIndex + "-" + UUID.randomUUID();

            String payload = """
                    {
                      "currency": "USD",
                      "items": [
                        {
                          "productId": "%s",
                          "quantity": 1,
                          "unitPrice": 15.00
                        }
                      ]
                    }
                    """.formatted(product.id());

            executor.submit(() -> {
                // Signal that this worker is initialized and ready
                readyLatch.countDown();
                long requestStart = 0;
                try {
                    // Block until ALL 100 workers are ready and the barrier is tripped
                    startBarrier.await();
                    requestStart = System.currentTimeMillis();

                    HttpRequest httpRequest = HttpRequest.newBuilder()
                            .uri(URI.create(targetUrl))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", idempotencyKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .timeout(Duration.ofSeconds(30))
                            .build();

                    HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                    long latency = System.currentTimeMillis() - requestStart;

                    int status = httpResponse.statusCode();
                    String body = httpResponse.body();
                    UUID orderId = null;
                    String failureReason = null;

                    if (status == 201) {
                        try {
                            JsonNode jsonNode = objectMapper.readTree(body);
                            orderId = UUID.fromString(jsonNode.get("id").asText());
                            successfulOrderIds.add(orderId);
                            successCount.incrementAndGet();
                        } catch (Exception ex) {
                            unexpectedErrorCount.incrementAndGet();
                            failureReason = "Malformed JSON in 201 response: " + ex.getMessage();
                        }
                    } else if (status == 409) {
                        // Inspect response body to strictly verify it represents InsufficientStockException
                        boolean isInsufficientStock = false;
                        String rejectionMessage = null;
                        try {
                            JsonNode jsonNode = objectMapper.readTree(body);
                            rejectionMessage = jsonNode.has("message") ? jsonNode.get("message").asText() : "";
                            boolean messageMatches = rejectionMessage.contains("Insufficient stock");
                            boolean detailsMatch = false;
                            if (jsonNode.has("details") && jsonNode.get("details").isArray()) {
                                for (JsonNode detail : jsonNode.get("details")) {
                                    if (detail.asText().contains("Insufficient stock")) {
                                        detailsMatch = true;
                                        break;
                                    }
                                }
                            }
                            if (messageMatches || detailsMatch) {
                                isInsufficientStock = true;
                            }
                        } catch (Exception e) {
                            failureReason = "Malformed JSON in 409 response: " + body;
                        }

                        if (isInsufficientStock) {
                            insufficientStockCount.incrementAndGet();
                            failedIdempotencyKeys.add(idempotencyKey);
                            failureReason = rejectionMessage;
                        } else {
                            // Any other conflict (e.g. idempotency collision, data integrity) is an unexpected error
                            unexpectedErrorCount.incrementAndGet();
                            failureReason = "HTTP 409 Conflict was NOT an insufficient-stock error: " + (rejectionMessage != null ? rejectionMessage : body);
                        }
                    } else {
                        unexpectedErrorCount.incrementAndGet();
                        failureReason = "Unexpected HTTP " + status + ": " + body;
                    }

                    responseRecords.add(new HttpResponseRecord(
                            requestIndex, idempotencyKey, status, body, orderId, failureReason, latency
                    ));
                } catch (Exception ex) {
                    unexpectedErrorCount.incrementAndGet();
                    long duration = requestStart > 0 ? (System.currentTimeMillis() - requestStart) : 0;
                    responseRecords.add(new HttpResponseRecord(
                            requestIndex, idempotencyKey, 0, null, null, ex.getMessage(), duration
                    ));
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // 4. Verify all 100 workers reach the barrier
        boolean allWorkersReady = readyLatch.await(15, TimeUnit.SECONDS);
        if (!allWorkersReady) {
            executor.shutdownNow();
            throw new AssertionError("Timed out waiting for all " + totalRequests + " worker threads to reach ready barrier state");
        }

        // Release all 100 threads simultaneously through the barrier
        startBarrier.countDown();
        boolean finishedInTime = completionLatch.await(90, TimeUnit.SECONDS);
        executor.shutdown();
        if (!finishedInTime) {
            executor.shutdownNow();
            throw new AssertionError("All " + totalRequests + " concurrent requests did not complete within 90 seconds");
        }

        long totalExecutionTime = System.currentTimeMillis() - startTime;

        // 5. Query final database inventory state
        Inventory finalInventory = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id()))
                .orElseThrow(() -> new IllegalStateException("Final inventory record missing"));

        // 6. Query orders from database
        List<Order> savedOrders = orderRepository.findAll().stream()
                .filter(o -> successfulOrderIds.contains(o.getId()))
                .toList();

        // 7. Independent PostgreSQL audit of ALL reservations for this test product and warehouse
        List<Reservation> allReservationsInDb = reservationRepository.findByProductIdAndWarehouseId(
                product.id(), warehouse.id()
        );
        long totalReservedQuantityInDb = allReservationsInDb.stream()
                .mapToLong(Reservation::getQuantity)
                .sum();

        // 8. Assertions verification
        boolean pass = true;
        List<String> assertionFailures = new ArrayList<>();

        if (successCount.get() != 10) {
            pass = false;
            assertionFailures.add("Expected exactly 10 successful purchases, but got: " + successCount.get());
        }
        if (insufficientStockCount.get() != 90) {
            pass = false;
            assertionFailures.add("Expected exactly 90 verified insufficient-stock rejections, but got: " + insufficientStockCount.get());
        }
        if (unexpectedErrorCount.get() != 0) {
            pass = false;
            assertionFailures.add("Expected 0 unexpected errors, but got: " + unexpectedErrorCount.get());
        }
        if (finalInventory.getAvailableQty() != 0L) {
            pass = false;
            assertionFailures.add("Expected final availableQty = 0, but got: " + finalInventory.getAvailableQty());
        }
        if (finalInventory.getReservedQty() != 10L) {
            pass = false;
            assertionFailures.add("Expected final reservedQty = 10, but got: " + finalInventory.getReservedQty());
        }
        if (finalInventory.getAvailableQty() < 0L) {
            pass = false;
            assertionFailures.add("Inventory availableQty became negative: " + finalInventory.getAvailableQty());
        }
        if (successfulOrderIds.size() != 10) {
            pass = false;
            assertionFailures.add("Expected exactly 10 distinct successful order IDs, but got: " + successfulOrderIds.size());
        }
        if (savedOrders.size() != 10) {
            pass = false;
            assertionFailures.add("Expected exactly 10 orders in database, but found: " + savedOrders.size());
        }
        for (Order o : savedOrders) {
            if (o.getStatus() != OrderStatus.PENDING_PAYMENT) {
                pass = false;
                assertionFailures.add("Order " + o.getId() + " is in unexpected state: " + o.getStatus());
            }
        }

        // Reservation table audit assertions
        if (allReservationsInDb.size() != 10) {
            pass = false;
            assertionFailures.add("Expected exactly 10 reservation rows for test product in DB, but found: " + allReservationsInDb.size());
        }
        if (totalReservedQuantityInDb != 10L) {
            pass = false;
            assertionFailures.add("Expected sum of reservation quantities = 10, but got: " + totalReservedQuantityInDb);
        }
        for (Reservation r : allReservationsInDb) {
            if (r.getStatus() != ReservationStatus.RESERVED || r.getQuantity() != 1L) {
                pass = false;
                assertionFailures.add("Reservation " + r.getId() + " has invalid status or quantity: " + r.getStatus() + ", " + r.getQuantity());
            }
            if (!successfulOrderIds.contains(r.getOrderId())) {
                pass = false;
                assertionFailures.add("Orphan reservation detected not belonging to any successful order: " + r.getId() + ", orderId: " + r.getOrderId());
            }
        }

        // Verify that failed requests left NO orders in the database
        for (String failedKey : failedIdempotencyKeys) {
            Optional<Order> ghostOrder = orderRepository.findByIdempotencyKey(failedKey);
            if (ghostOrder.isPresent()) {
                pass = false;
                assertionFailures.add("Found ghost order for rejected idempotency key: " + failedKey);
            }
        }

        // Every request outcome is persisted in order_requests: 10 ACCEPTED, 90 REJECTED_INSUFFICIENT_STOCK
        Map<String, Long> outcomes = orderService.requestOutcomeCounts("conc-order-" + runId + "-");
        if (outcomes.get("ACCEPTED") != 10L) {
            pass = false;
            assertionFailures.add("Expected 10 ACCEPTED order_requests rows, but got: " + outcomes.get("ACCEPTED"));
        }
        if (outcomes.get("REJECTED_INSUFFICIENT_STOCK") != 90L) {
            pass = false;
            assertionFailures.add("Expected 90 REJECTED_INSUFFICIENT_STOCK order_requests rows, but got: " + outcomes.get("REJECTED_INSUFFICIENT_STOCK"));
        }

        // 9. Print concise test report
        System.out.println("""
            ================================================================================
                                POSTGRES CONCURRENCY TEST REPORT
            ================================================================================
            Target Endpoint:                 POST /api/orders (Real Tomcat HTTP Port: %d)
            Database Profile:                postgres-test (PostgreSQL source of truth)
            Product ID:                      %s
            Warehouse ID:                    %s
            --------------------------------------------------------------------------------
            Total requests:                  %d
            Workers ready at barrier:        %d
            Successful purchases (201):      %d
            Verified stock rejections (409): %d
            Unexpected errors:               %d
            Final available quantity:        %d
            Final reserved quantity:         %d
            Total reservations in DB:        %d
            Total reserved units in DB:      %d
            Total execution time:            %d ms
            --------------------------------------------------------------------------------
            RESULT:                          %s
            ================================================================================
            """.formatted(
                port,
                product.id(),
                warehouse.id(),
                totalRequests,
                totalRequests,
                successCount.get(),
                insufficientStockCount.get(),
                unexpectedErrorCount.get(),
                finalInventory.getAvailableQty(),
                finalInventory.getReservedQty(),
                allReservationsInDb.size(),
                totalReservedQuantityInDb,
                totalExecutionTime,
                pass ? "PASS" : "FAIL"
        ));

        if (!pass) {
            System.err.println("Assertion Failures:");
            for (String failure : assertionFailures) {
                System.err.println(" - " + failure);
            }
        }

        // 10. Assert JUnit conditions
        assertThat(assertionFailures).as("Concurrency test assertions must have zero failures").isEmpty();
        assertThat(pass).as("Test result must be PASS").isTrue();
    }
}
