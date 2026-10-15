package com.assignment.fulfilment.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.inventory.InventoryRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.ReservationRepository;
import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.orders.OrderRepository;
import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import com.assignment.fulfilment.payments.PaymentRepository;
import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationRecord;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRecordResponse;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRunResponse;
import com.assignment.fulfilment.reconciliation.dto.ResolveMismatchRequest;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
class ReconciliationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationRecordRepository reconciliationRecordRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private OrderResponse createOrderHelper(long qty, long stock) {
        String u = uid();
        ProductResponse p = catalogService.createProduct(
                new CreateProductRequest("SKU-REC-" + u, "Rec Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-REC-" + u, "Rec WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(p.id(), wh.id(), stock));

        String key = "idemp-rec-" + UUID.randomUUID();
        return orderService.createOrder(
                key,
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(p.id(), qty, new BigDecimal("25.00"))))
        );
    }

    @Test
    @DisplayName("Detect Mismatch 1: Payment SUCCEEDED but Order is CANCELLED")
    void testMismatch1PaymentSucceededOrderCancelled() {
        OrderResponse order = createOrderHelper(2L, 10L);
        orderService.cancelOrder(order.id());

        // Create succeeded payment for cancelled order
        Payment payment = new Payment(order.id(), "ref-" + UUID.randomUUID(), PaymentStatus.SUCCEEDED, new BigDecimal("50.00"), "USD");
        paymentRepository.save(payment);

        ReconciliationRunResponse run = reconciliationService.runReconciliation();
        assertThat(run.records()).isNotEmpty();

        String mismatchKey = "PAYMENT_SUCCEEDED_ORDER_CANCELLED:" + order.id() + ":" + payment.getId();
        ReconciliationRecord record = reconciliationRecordRepository.findByMismatchKey(mismatchKey).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(ReconciliationStatus.ACTION_REQUIRED);
        assertThat(record.getDetails()).contains("REFUND_REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("Detect Mismatch 2: Order is CONFIRMED or FULFILLED but successful payment is missing")
    void testMismatch2MissingPaymentConfirmedOrder() throws Exception {
        OrderResponse order = createOrderHelper(2L, 10L);

        // Transition order to CONFIRMED directly without creating a SUCCEEDED payment
        Order ord = orderRepository.findById(order.id()).orElseThrow();
        ord.confirm();
        orderRepository.save(ord);

        ReconciliationRunResponse run = reconciliationService.runReconciliation();

        String mismatchKey = "MISSING_PAYMENT_CONFIRMED_ORDER:" + order.id();
        ReconciliationRecord record = reconciliationRecordRepository.findByMismatchKey(mismatchKey).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(ReconciliationStatus.ACTION_REQUIRED);
        assertThat(record.getMismatchType()).isEqualTo("MISSING_PAYMENT_CONFIRMED_ORDER");
        assertThat(record.getDetails()).contains("PAYMENT_AUDIT_REQUIRED");
    }

    @Test
    @DisplayName("Detect Mismatch 3: Inconsistent reservation and order states (Cancelled order has active reservations)")
    void testMismatch3ReservationStateInconsistency() {
        OrderResponse order = createOrderHelper(2L, 10L);

        // Cancel order status in DB, but artificially simulate an unreleased reservation
        Order ord = orderRepository.findById(order.id()).orElseThrow();
        ord.cancel();
        orderRepository.save(ord);

        // Ensure reservation is RESERVED
        List<Reservation> reservations = reservationRepository.findByOrderId(order.id());
        assertThat(reservations).isNotEmpty();

        ReconciliationRunResponse run = reconciliationService.runReconciliation();

        String mismatchKey = "RESERVATION_STATE_INCONSISTENCY:" + order.id();
        ReconciliationRecord record = reconciliationRecordRepository.findByMismatchKey(mismatchKey).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(ReconciliationStatus.ACTION_REQUIRED);
        assertThat(record.getDetails()).contains("INVENTORY_RELEASE_AUDIT_REQUIRED");
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Detect Mismatch 4: Stale pending orders and payments beyond configurable age")
    void testMismatch4StalePendingOrderAndPayment() throws Exception {
        OrderResponse order = createOrderHelper(1L, 10L);

        // Age the order artificially by modifying createdAt field in database
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(twoHoursAgo), order.id());

        // Create stale payment and age it in database
        Payment payment = new Payment(order.id(), "stale-ref-" + UUID.randomUUID(), PaymentStatus.PENDING, new BigDecimal("25.00"), "USD");
        payment = paymentRepository.save(payment);
        jdbcTemplate.update("UPDATE payments SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(twoHoursAgo), payment.getId());

        ReconciliationRunResponse run = reconciliationService.runReconciliation();

        // Stale order detected
        String orderMismatchKey = "STALE_PENDING_ORDER:" + order.id();
        ReconciliationRecord ordRecord = reconciliationRecordRepository.findByMismatchKey(orderMismatchKey).orElseThrow();
        assertThat(ordRecord.getStatus()).isEqualTo(ReconciliationStatus.OPEN);
        assertThat(ordRecord.getDetails()).contains("STALE_ORDER_REVIEW_REQUIRED");

        // Stale payment detected
        String payMismatchKey = "STALE_PENDING_PAYMENT:" + payment.getId();
        ReconciliationRecord payRecord = reconciliationRecordRepository.findByMismatchKey(payMismatchKey).orElseThrow();
        assertThat(payRecord.getStatus()).isEqualTo(ReconciliationStatus.OPEN);
        assertThat(payRecord.getDetails()).contains("STALE_PAYMENT_SYNC_REQUIRED");
    }

    @Test
    @DisplayName("Repeated runs: do not create duplicate records or re-execute actions")
    void testRepeatedRunsIdempotency() {
        OrderResponse order = createOrderHelper(2L, 10L);
        orderService.cancelOrder(order.id());

        Payment payment = new Payment(order.id(), "ref-rep-" + UUID.randomUUID(), PaymentStatus.SUCCEEDED, new BigDecimal("50.00"), "USD");
        paymentRepository.save(payment);

        // Run 1
        ReconciliationRunResponse run1 = reconciliationService.runReconciliation();
        int initialDetected = run1.detectedCount();
        assertThat(initialDetected).isGreaterThan(0);

        // Run 2: same state
        ReconciliationRunResponse run2 = reconciliationService.runReconciliation();
        // Zero new detections for already detected mismatches
        assertThat(run2.detectedCount()).isEqualTo(0);
        assertThat(run2.existingCount()).isGreaterThanOrEqualTo(initialDetected);

        // Verify count of records in DB
        String mismatchKey = "PAYMENT_SUCCEEDED_ORDER_CANCELLED:" + order.id() + ":" + payment.getId();
        List<ReconciliationRecord> records = reconciliationRecordRepository.findAll().stream()
                .filter(r -> r.getMismatchKey().equals(mismatchKey))
                .toList();
        assertThat(records).hasSize(1);
    }

    @Test
    @DisplayName("Simulated action resolution: idempotent and records audit log")
    void testActionResolutionAndAuditHistory() {
        OrderResponse order = createOrderHelper(2L, 10L);
        orderService.cancelOrder(order.id());

        Payment payment = new Payment(order.id(), "ref-res-" + UUID.randomUUID(), PaymentStatus.SUCCEEDED, new BigDecimal("50.00"), "USD");
        paymentRepository.save(payment);

        reconciliationService.runReconciliation();

        String mismatchKey = "PAYMENT_SUCCEEDED_ORDER_CANCELLED:" + order.id() + ":" + payment.getId();
        ReconciliationRecord record = reconciliationRecordRepository.findByMismatchKey(mismatchKey).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(ReconciliationStatus.ACTION_REQUIRED);

        // 1. Resolve mismatch
        ReconciliationRecordResponse resolved = reconciliationService.resolveRecord(
                record.getId(),
                new ResolveMismatchRequest("RESOLVE", "Simulated refund processed and verified")
        );
        assertThat(resolved.status()).isEqualTo(ReconciliationStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isNotNull();

        // 2. Audit log recorded
        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityId("reconciliation", record.getId().toString());
        assertThat(auditLogs).isNotEmpty();
        assertThat(auditLogs.stream().anyMatch(a -> a.getAction().equals("MISMATCH_RESOLVED"))).isTrue();

        // 3. Repeated resolution is safe & idempotent
        ReconciliationRecordResponse repeatedResolve = reconciliationService.resolveRecord(
                record.getId(),
                new ResolveMismatchRequest("RESOLVE", "Second attempt")
        );
        assertThat(repeatedResolve.status()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
