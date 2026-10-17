package com.assignment.fulfilment.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import com.assignment.fulfilment.payments.PaymentRepository;
import com.assignment.fulfilment.payments.PaymentService;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import com.assignment.fulfilment.payments.dto.PaymentCallbackRequest;
import com.assignment.fulfilment.payments.dto.PaymentCallbackResponse;
import com.assignment.fulfilment.payments.dto.PaymentResponse;
import com.assignment.fulfilment.payments.dto.SimulatePaymentRequest;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationRecord;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRecordResponse;
import com.assignment.fulfilment.reconciliation.dto.ResolveMismatchRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentReconciliationFlowTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationRecordRepository reconciliationRecordRepository;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private MockMvc mockMvc;

    /** Order of 2 x 20.00 = 40.00 USD. */
    private OrderResponse order() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        ProductResponse p = catalogService.createProduct(new CreateProductRequest("SKU-REC-" + u, "Reconciliation Product", null, true));
        WarehouseResponse w = catalogService.createWarehouse(new CreateWarehouseRequest("WH-REC-" + u, "Reconciliation WH", null, true));
        inventoryService.adjust(new AdjustInventoryRequest(p.id(), w.id(), 10L));
        return orderService.createOrder("rec-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(p.id(), 2L, new BigDecimal("20.00")))));
    }

    private List<ReconciliationRecordResponse> records(UUID orderId, String type) {
        return reconciliationService.recordsForOrder(orderId).stream().filter(r -> r.mismatchType().equals(type)).toList();
    }

    @Test
    @DisplayName("Simulated success persists the provider callback, so the webhook audit trail is not empty")
    void simulatedPaymentRecordsCallback() {
        OrderResponse order = order();
        PaymentResponse payment = paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("SUCCEEDED", null, null));

        List<PaymentCallbackResponse> callbacks = paymentService.listCallbacks(payment.id());
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks.get(0).result()).isEqualTo("SUCCEEDED");
        assertThat(payment.lastCallbackId()).isEqualTo(callbacks.get(0).providerCallbackId());
        assertThat(payment.amount()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("Duplicate callback: ignored without state change and visible in the audit trail")
    void duplicateCallbackIsAudited() {
        OrderResponse order = order();
        PaymentResponse timedOut = paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("TIMED_OUT", null, null));
        PaymentCallbackRequest callback = new PaymentCallbackRequest("cb-" + UUID.randomUUID(), timedOut.id(), null, "SUCCEEDED");

        paymentService.processCallback(callback);
        PaymentResponse again = paymentService.processCallback(callback);
        // A contradictory callback arriving after the payment is final is recorded but not applied
        paymentService.processCallback(new PaymentCallbackRequest("cb-" + UUID.randomUUID(), timedOut.id(), null, "FAILED"));

        assertThat(again.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentRepository.findById(timedOut.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentService.listCallbacks(timedOut.id())).hasSize(2);
        assertThat(auditLogRepository.findByEntityTypeAndEntityId("payment", timedOut.id().toString()))
                .extracting(a -> a.getAction())
                .contains("PAYMENT_CALLBACK_APPLIED", "PAYMENT_CALLBACK_DUPLICATE_IGNORED", "PAYMENT_CALLBACK_IGNORED");
    }

    @Test
    @DisplayName("Late success after cancellation: one mismatch record, refund corrective action, audited")
    void lateSuccessIsRefundedThroughReconciliation() {
        OrderResponse order = order();
        PaymentResponse timedOut = paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("TIMED_OUT", null, null));
        orderService.cancelOrder(order.id());
        paymentService.processCallback(new PaymentCallbackRequest("cb-late-" + UUID.randomUUID(), timedOut.id(), null, "SUCCEEDED"));

        reconciliationService.runReconciliation();
        reconciliationService.runReconciliation();

        List<ReconciliationRecordResponse> mismatches = records(order.id(), "PAYMENT_SUCCEEDED_ORDER_CANCELLED");
        assertThat(mismatches).as("the inline record and the reconciliation run must not duplicate the incident").hasSize(1);

        ReconciliationRecordResponse resolved = reconciliationService.resolveRecord(
                mismatches.get(0).id(), new ResolveMismatchRequest("REFUND_PAYMENT", "Customer refunded"));

        assertThat(resolved.status()).isEqualTo(ReconciliationStatus.RESOLVED);
        assertThat(resolved.resolutionAction()).isEqualTo("REFUND_PAYMENT");
        assertThat(resolved.resolutionNotes()).contains("REFUNDED");
        assertThat(paymentRepository.findById(timedOut.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(auditLogRepository.findByEntityTypeAndEntityId("payment", timedOut.id().toString()))
                .anyMatch(a -> a.getAction().equals("PAYMENT_REFUNDED"));

        // Resolving again is a no-op; a new run does not reopen the refunded incident
        reconciliationService.resolveRecord(mismatches.get(0).id(), new ResolveMismatchRequest("REFUND_PAYMENT", null));
        reconciliationService.runReconciliation();
        assertThat(records(order.id(), "PAYMENT_SUCCEEDED_ORDER_CANCELLED")).hasSize(1);
    }

    @Test
    @DisplayName("Double charge and wrong amount are detected against the persisted order")
    void duplicateChargeAndAmountMismatchDetected() {
        OrderResponse order = order();
        paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("SUCCEEDED", null, null));
        paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("SUCCEEDED", new BigDecimal("39.00"), "USD"));

        reconciliationService.runReconciliation();

        assertThat(records(order.id(), "DUPLICATE_SUCCESSFUL_PAYMENT")).hasSize(1);
        List<ReconciliationRecordResponse> amount = records(order.id(), "PAYMENT_AMOUNT_MISMATCH");
        assertThat(amount).hasSize(1);
        assertThat(amount.get(0).details()).contains("\"paidAmount\":39.00");
        assertThat(auditLogRepository.findAll()).anyMatch(a -> a.getAction().equals("RECONCILIATION_RUN"));
    }

    @Test
    @DisplayName("Corrective actions are validated against the mismatch type")
    void inapplicableActionRejected() {
        OrderResponse order = order();
        paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("SUCCEEDED", null, null));
        paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("SUCCEEDED", null, null));
        reconciliationService.runReconciliation();
        ReconciliationRecord duplicate = reconciliationRecordRepository.findByMismatchKey("DUPLICATE_SUCCESSFUL_PAYMENT:" + order.id()).orElseThrow();

        assertThatThrownBy(() -> reconciliationService.resolveRecord(duplicate.getId(), new ResolveMismatchRequest("EXPIRE_ORDER", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reconciliationRecordRepository.findById(duplicate.getId()).orElseThrow().getStatus())
                .isEqualTo(ReconciliationStatus.ACTION_REQUIRED);
    }

    @Test
    @DisplayName("Order details API assembles lines, reservations, history, payments, callbacks and events")
    void orderDetailsEndpoint() throws Exception {
        OrderResponse order = order();
        paymentService.simulatePayment(order.id(), new SimulatePaymentRequest("SUCCEEDED", null, null));

        mockMvc.perform(get("/api/orders/" + order.id() + "/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reservationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[0].sku").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.reservations[0].warehouseCode").isNotEmpty())
                .andExpect(jsonPath("$.statusHistory[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.statusHistory[1].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payments[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.callbacks.length()").value(1))
                .andExpect(jsonPath("$.events[?(@.eventType == 'ORDER_CREATED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType == 'PAYMENT_SUCCEEDED')]").exists())
                .andExpect(jsonPath("$.requests[0].outcome").value("ACCEPTED"));
    }
}
