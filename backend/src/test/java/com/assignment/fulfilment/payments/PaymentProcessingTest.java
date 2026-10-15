package com.assignment.fulfilment.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.exception.InvalidOrderStateTransitionException;
import com.assignment.fulfilment.inventory.InventoryRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.orders.OrderRepository;
import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import com.assignment.fulfilment.payments.dto.PaymentCallbackRequest;
import com.assignment.fulfilment.payments.dto.PaymentResponse;
import com.assignment.fulfilment.payments.dto.SimulatePaymentRequest;
import com.assignment.fulfilment.reconciliation.ReconciliationRecordRepository;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationRecord;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
class PaymentProcessingTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

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
    private ReconciliationRecordRepository reconciliationRecordRepository;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private OrderResponse createTestOrder(long qty, long stock) {
        String u = uid();
        ProductResponse p = catalogService.createProduct(
                new CreateProductRequest("SKU-PAY-" + u, "Payment Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-PAY-" + u, "Payment WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(p.id(), wh.id(), stock));

        String key = "idemp-pay-" + UUID.randomUUID();
        return orderService.createOrder(
                key,
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(p.id(), qty, new BigDecimal("20.00"))))
        );
    }

    @Test
    @DisplayName("Payment success: transitions payment to SUCCEEDED and confirms PENDING_PAYMENT order")
    void testPaymentSuccessConfirmsOrder() {
        OrderResponse order = createTestOrder(2L, 10L);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        PaymentResponse payment = paymentService.simulatePayment(
                order.id(),
                new SimulatePaymentRequest("SUCCEEDED", new BigDecimal("40.00"), "USD")
        );

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.succeededAt()).isNotNull();

        // Order transitioned to CONFIRMED
        Order updatedOrder = orderRepository.findById(order.id()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(updatedOrder.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("Payment failure: records FAILED status, order remains PENDING_PAYMENT")
    void testPaymentFailureLeavesOrderPending() {
        OrderResponse order = createTestOrder(2L, 10L);

        PaymentResponse payment = paymentService.simulatePayment(
                order.id(),
                new SimulatePaymentRequest("FAILED", new BigDecimal("40.00"), "USD")
        );

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.succeededAt()).isNull();

        Order updatedOrder = orderRepository.findById(order.id()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("Payment timeout: records TIMED_OUT status, order remains PENDING_PAYMENT (uncertain outcome)")
    void testPaymentTimeoutLeavesOrderPending() {
        OrderResponse order = createTestOrder(2L, 10L);

        PaymentResponse payment = paymentService.simulatePayment(
                order.id(),
                new SimulatePaymentRequest("TIMED_OUT", new BigDecimal("40.00"), "USD")
        );

        assertThat(payment.status()).isEqualTo(PaymentStatus.TIMED_OUT);
        assertThat(payment.succeededAt()).isNull();

        Order updatedOrder = orderRepository.findById(order.id()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("Delayed callback: refines TIMED_OUT payment to SUCCEEDED and confirms order")
    void testDelayedSuccessCallbackRefinesTimedOutPayment() {
        OrderResponse order = createTestOrder(2L, 10L);

        PaymentResponse payment = paymentService.simulatePayment(
                order.id(),
                new SimulatePaymentRequest("TIMED_OUT", new BigDecimal("40.00"), "USD")
        );
        assertThat(payment.status()).isEqualTo(PaymentStatus.TIMED_OUT);

        // Delayed callback arrives
        String callbackId = "cb-delayed-" + UUID.randomUUID();
        PaymentResponse callbackResponse = paymentService.processCallback(
                new PaymentCallbackRequest(callbackId, payment.id(), payment.providerReference(), "SUCCEEDED")
        );

        assertThat(callbackResponse.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(callbackResponse.succeededAt()).isNotNull();
        assertThat(callbackResponse.lastCallbackId()).isEqualTo(callbackId);

        // Order is confirmed
        Order updatedOrder = orderRepository.findById(order.id()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Duplicate callback ID: ignored idempotently without duplicating effects")
    void testDuplicateCallbackIgnored() {
        OrderResponse order = createTestOrder(2L, 10L);

        PaymentResponse payment = paymentService.simulatePayment(
                order.id(),
                new SimulatePaymentRequest("TIMED_OUT", new BigDecimal("40.00"), "USD")
        );

        String callbackId = "cb-dup-" + UUID.randomUUID();
        PaymentCallbackRequest cbRequest = new PaymentCallbackRequest(
                callbackId, payment.id(), payment.providerReference(), "SUCCEEDED"
        );

        // First callback execution
        PaymentResponse res1 = paymentService.processCallback(cbRequest);
        assertThat(res1.status()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Second identical callback with same callbackId
        PaymentResponse res2 = paymentService.processCallback(cbRequest);
        assertThat(res2.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(res2.id()).isEqualTo(res1.id());

        // Order remains confirmed once
        Order updatedOrder = orderRepository.findById(order.id()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Late success after cancellation: does NOT resurrect order or reserve stock, records reconciliation record")
    void testLateSuccessAfterCancellation() {
        OrderResponse order = createTestOrder(3L, 10L);
        UUID productId = order.items().get(0).productId();

        // Cancel order -> CANCELLED, stock released
        orderService.cancelOrder(order.id());

        Order cancelledOrder = orderRepository.findById(order.id()).orElseThrow();
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // Late payment simulation arrives as SUCCEEDED
        PaymentResponse payment = paymentService.simulatePayment(
                order.id(),
                new SimulatePaymentRequest("SUCCEEDED", new BigDecimal("60.00"), "USD")
        );

        // Payment status is SUCCEEDED (customer was charged)
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Order MUST NOT be resurrected!
        Order orderAfterPayment = orderRepository.findById(order.id()).orElseThrow();
        assertThat(orderAfterPayment.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // Inventory MUST NOT be re-reserved! Available is still 10, reserved is 0
        Inventory inv = inventoryRepository.findAll().stream()
                .filter(i -> i.getId().getProductId().equals(productId))
                .findFirst().orElseThrow();
        assertThat(inv.getAvailableQty()).isEqualTo(10L);
        assertThat(inv.getReservedQty()).isEqualTo(0L);

        // Contradictory outcome recorded for reconciliation
        String mismatchKey = "LATE_PAYMENT_CANCELLED_ORDER:" + order.id();
        ReconciliationRecord record = reconciliationRecordRepository.findByMismatchKey(mismatchKey).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(ReconciliationStatus.ACTION_REQUIRED);
        assertThat(record.getMismatchType()).isEqualTo("PAYMENT_SUCCEEDED_ORDER_CANCELLED");
        assertThat(record.getDetails()).contains("REFUND_REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("Race condition between cancellation and payment: row locking guarantees consistency")
    void testCancellationAndPaymentRace() throws Exception {
        OrderResponse order = createTestOrder(2L, 10L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicBoolean cancelSucceeded = new AtomicBoolean(false);
        AtomicBoolean paymentSucceeded = new AtomicBoolean(false);
        AtomicReference<Exception> cancelError = new AtomicReference<>();

        // Thread 1: Cancel order
        executor.submit(() -> {
            try {
                startLatch.await();
                orderService.cancelOrder(order.id());
                cancelSucceeded.set(true);
            } catch (Exception e) {
                cancelError.set(e);
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Payment success
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.simulatePayment(
                        order.id(),
                        new SimulatePaymentRequest("SUCCEEDED", new BigDecimal("40.00"), "USD")
                );
                paymentSucceeded.set(true);
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        Order finalOrder = orderRepository.findById(order.id()).orElseThrow();

        if (cancelSucceeded.get()) {
            // Cancellation won the race or committed:
            // Order must be CANCELLED
            assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

            // If payment also recorded SUCCEEDED, reconciliation record must exist
            if (paymentSucceeded.get()) {
                String mismatchKey = "LATE_PAYMENT_CANCELLED_ORDER:" + order.id();
                assertThat(reconciliationRecordRepository.findByMismatchKey(mismatchKey)).isPresent();
            }
        } else {
            // Payment won the race and confirmed order:
            // Cancellation was rejected with InvalidOrderStateTransitionException (409)
            assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(cancelError.get()).isInstanceOf(InvalidOrderStateTransitionException.class);
        }
    }
}
