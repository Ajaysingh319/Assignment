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
import java.util.Optional;
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
 * Rules:
 * - Uses real PostgreSQL and the 'postgres-test' profile (strictly disallows H2 or mocks).
 * - Fires 100 concurrent HTTP POST requests to the actual Spring Boot HTTP endpoint (/api/orders).
 * - Uses unique idempotency keys per request.
 * - Uses a synchronization barrier (CountDownLatch) to ensure concurrent request initiation.
 * - Records every HTTP status, response body, order ID, and failure reason.
 * - Verifies zero oversell: exactly 10 succeed (HTTP 201), exactly 90 fail with insufficient stock (HTTP 409).
 * - Verifies database state: availableQty = 0, reservedQty = 10, exactly 10 distinct orders in DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres-test")
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

        // 3. Prepare 100 concurrent HTTP purchase requests
        int totalRequests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch barrierLatch = new CountDownLatch(1);
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
                long requestStart = System.currentTimeMillis();
                try {
                    // Synchronized start barrier: all 100 threads wait here to burst together
                    barrierLatch.await();

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
                        successCount.incrementAndGet();
                        JsonNode jsonNode = objectMapper.readTree(body);
                        orderId = UUID.fromString(jsonNode.get("id").asText());
                        successfulOrderIds.add(orderId);
                    } else if (status == 409) {
                        insufficientStockCount.incrementAndGet();
                        failedIdempotencyKeys.add(idempotencyKey);
                        try {
                            JsonNode jsonNode = objectMapper.readTree(body);
                            failureReason = jsonNode.has("message") ? jsonNode.get("message").asText() : body;
                        } catch (Exception e) {
                            failureReason = body;
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
                    responseRecords.add(new HttpResponseRecord(
                            requestIndex, idempotencyKey, 0, null, null, ex.getMessage(),
                            System.currentTimeMillis() - requestStart
                    ));
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Release all 100 threads simultaneously through the barrier
        barrierLatch.countDown();
        boolean finishedInTime = completionLatch.await(90, TimeUnit.SECONDS);
        assertThat(finishedInTime).as("All 100 concurrent requests must complete within 90 seconds").isTrue();
        executor.shutdown();

        long totalExecutionTime = System.currentTimeMillis() - startTime;

        // 4. Query final database inventory state
        Inventory finalInventory = inventoryRepository.findById(new InventoryId(product.id(), warehouse.id()))
                .orElseThrow(() -> new IllegalStateException("Final inventory record missing"));

        // 5. Query orders and reservations from database
        List<Order> savedOrders = orderRepository.findAll().stream()
                .filter(o -> successfulOrderIds.contains(o.getId()))
                .toList();

        List<Reservation> productReservations = reservationRepository.findByOrderId(
                savedOrders.isEmpty() ? UUID.randomUUID() : savedOrders.get(0).getId()
        );
        // Query all reservations for this product across all orders
        List<Reservation> allReservationsForProduct = new ArrayList<>();
        for (UUID sId : successfulOrderIds) {
            allReservationsForProduct.addAll(reservationRepository.findByOrderId(sId));
        }

        // 6. Assertions verification
        boolean pass = true;
        List<String> assertionFailures = new ArrayList<>();

        if (successCount.get() != 10) {
            pass = false;
            assertionFailures.add("Expected exactly 10 successful purchases, but got: " + successCount.get());
        }
        if (insufficientStockCount.get() != 90) {
            pass = false;
            assertionFailures.add("Expected exactly 90 insufficient-stock rejections, but got: " + insufficientStockCount.get());
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
        if (allReservationsForProduct.size() != 10) {
            pass = false;
            assertionFailures.add("Expected exactly 10 active reservations for product, but found: " + allReservationsForProduct.size());
        }
        for (Reservation r : allReservationsForProduct) {
            if (r.getStatus() != ReservationStatus.RESERVED || r.getQuantity() != 1L) {
                pass = false;
                assertionFailures.add("Reservation " + r.getId() + " has invalid status or quantity: " + r.getStatus() + ", " + r.getQuantity());
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

        // 7. Print concise test report
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
            Successful purchases:            %d
            Insufficient-stock rejections:   %d
            Unexpected errors:               %d
            Final available quantity:        %d
            Final reserved quantity:         %d
            Total execution time:            %d ms
            --------------------------------------------------------------------------------
            RESULT:                          %s
            ================================================================================
            """.formatted(
                port,
                product.id(),
                warehouse.id(),
                totalRequests,
                successCount.get(),
                insufficientStockCount.get(),
                unexpectedErrorCount.get(),
                finalInventory.getAvailableQty(),
                finalInventory.getReservedQty(),
                totalExecutionTime,
                pass ? "PASS" : "FAIL"
        ));

        if (!pass) {
            System.err.println("Assertion Failures:");
            for (String failure : assertionFailures) {
                System.err.println(" - " + failure);
            }
        }

        // 8. Assert JUnit conditions
        assertThat(assertionFailures).as("Concurrency test assertions must have zero failures").isEmpty();
        assertThat(pass).as("Test result must be PASS").isTrue();
    }
}
