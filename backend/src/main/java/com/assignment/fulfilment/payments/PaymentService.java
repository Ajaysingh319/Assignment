package com.assignment.fulfilment.payments;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.publisher.OutboxService;
import com.assignment.fulfilment.inventory.InventoryReservationService;
import com.assignment.fulfilment.orders.OrderRepository;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentCallback;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import com.assignment.fulfilment.payments.dto.PaymentCallbackRequest;
import com.assignment.fulfilment.payments.dto.PaymentResponse;
import com.assignment.fulfilment.payments.dto.SimulatePaymentRequest;
import com.assignment.fulfilment.reconciliation.ReconciliationRecordRepository;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationRecord;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentCallbackRepository paymentCallbackRepository;
    private final OrderRepository orderRepository;
    private final InventoryReservationService inventoryReservationService;
    private final ReconciliationRecordRepository reconciliationRecordRepository;
    private final OutboxService outboxService;
    private final AuditLogRepository auditLogRepository;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentCallbackRepository paymentCallbackRepository,
            OrderRepository orderRepository,
            InventoryReservationService inventoryReservationService,
            ReconciliationRecordRepository reconciliationRecordRepository,
            OutboxService outboxService,
            AuditLogRepository auditLogRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentCallbackRepository = paymentCallbackRepository;
        this.orderRepository = orderRepository;
        this.inventoryReservationService = inventoryReservationService;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
        this.outboxService = outboxService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Simulates a payment outcome (SUCCEEDED, FAILED, TIMED_OUT).
     * Enforces deterministic state transitions and row-level locking.
     */
    @Transactional
    public PaymentResponse simulatePayment(UUID orderId, SimulatePaymentRequest request) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (request == null || request.outcome() == null) {
            throw new IllegalArgumentException("request and outcome must not be null");
        }

        // Row-level lock on Order prevents races with concurrent cancellations
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        BigDecimal amount = request.amount() != null ? request.amount() : order.getTotalAmount();
        String currency = request.currency() != null ? request.currency() : order.getCurrency();
        String providerReference = "sim_ref_" + UUID.randomUUID().toString().substring(0, 12);

        PaymentStatus outcomeStatus;
        try {
            outcomeStatus = PaymentStatus.valueOf(request.outcome().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid payment outcome: " + request.outcome());
        }

        Payment payment = new Payment(orderId, providerReference, outcomeStatus, amount, currency);
        Payment saved = paymentRepository.save(payment);

        if (outcomeStatus == PaymentStatus.SUCCEEDED) {
            handlePaymentSucceeded(order, saved);
        } else if (outcomeStatus == PaymentStatus.FAILED) {
            log.info("Payment {} simulated as FAILED for order {}", saved.getId(), orderId);
            auditLogRepository.save(new AuditLog(
                    "payment",
                    saved.getId().toString(),
                    "PAYMENT_FAILED",
                    "SYSTEM",
                    "Simulated failure for order=" + orderId
            ));
        } else if (outcomeStatus == PaymentStatus.TIMED_OUT) {
            log.info("Payment {} simulated as TIMED_OUT for order {}", saved.getId(), orderId);
            auditLogRepository.save(new AuditLog(
                    "payment",
                    saved.getId().toString(),
                    "PAYMENT_TIMED_OUT",
                    "SYSTEM",
                    "Simulated timeout (uncertain outcome) for order=" + orderId
            ));
        }

        outboxService.publish(
                "PAYMENT",
                saved.getId().toString(),
                "PAYMENT_" + outcomeStatus,
                1,
                "{\"paymentId\":\"" + saved.getId() + "\",\"orderId\":\"" + orderId + "\",\"status\":\"" + outcomeStatus + "\",\"amount\":" + amount + "}",
                null
        );

        return PaymentResponse.from(saved);
    }

    /**
     * Processes simulated payment callback with unique callback ID.
     * Idempotent: duplicate callback IDs are ignored and do not duplicate payment or order effects.
     */
    @Transactional
    public PaymentResponse processCallback(PaymentCallbackRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.callbackId() == null || request.callbackId().isBlank()) {
            throw new IllegalArgumentException("callbackId must not be blank");
        }
        if (request.paymentId() == null) {
            throw new IllegalArgumentException("paymentId must not be null");
        }

        // Fast check: duplicate callback ID deduplication
        if (paymentCallbackRepository.existsByProviderCallbackId(request.callbackId())) {
            log.info("Duplicate callback ID {} detected. Skipping duplicate callback processing.", request.callbackId());
            Payment payment = paymentRepository.findById(request.paymentId())
                    .orElseThrow(() -> new NoSuchElementException("Payment not found: " + request.paymentId()));
            return PaymentResponse.from(payment);
        }

        // Lock payment and associated order to prevent race conditions
        Payment payment = paymentRepository.findByIdForUpdate(request.paymentId())
                .orElseThrow(() -> new NoSuchElementException("Payment not found: " + request.paymentId()));

        Order order = orderRepository.findByIdForUpdate(payment.getOrderId())
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + payment.getOrderId()));

        PaymentCallback callback = new PaymentCallback(
                request.callbackId(),
                payment.getId(),
                "result=" + request.result() + ", providerReference=" + request.providerReference(),
                request.result()
        );
        paymentCallbackRepository.save(callback);

        String result = request.result().trim().toUpperCase();
        if ("SUCCEEDED".equals(result)) {
            // A late callback can refine a timed-out or pending payment to SUCCEEDED
            if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.TIMED_OUT) {
                payment.markSucceeded(request.callbackId());
                paymentRepository.save(payment);
                handlePaymentSucceeded(order, payment);
            }
        } else if ("FAILED".equals(result)) {
            if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.TIMED_OUT) {
                payment.markFailed(request.callbackId());
                paymentRepository.save(payment);
            }
        } else if ("TIMED_OUT".equals(result)) {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.markTimedOut();
                paymentRepository.save(payment);
            }
        }

        outboxService.publish(
                "PAYMENT",
                payment.getId().toString(),
                "PAYMENT_CALLBACK_PROCESSED",
                1,
                "{\"paymentId\":\"" + payment.getId() + "\",\"callbackId\":\"" + request.callbackId() + "\",\"result\":\"" + result + "\"}",
                null
        );

        return PaymentResponse.from(payment);
    }

    private void handlePaymentSucceeded(Order order, Payment payment) {
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            order.confirm();
            orderRepository.save(order);
            inventoryReservationService.confirm(order.getId());

            outboxService.publish(
                    "ORDER",
                    order.getId().toString(),
                    "ORDER_CONFIRMED",
                    1,
                    "{\"orderId\":\"" + order.getId() + "\",\"status\":\"CONFIRMED\"}",
                    null
            );

            auditLogRepository.save(new AuditLog(
                    "order",
                    order.getId().toString(),
                    "ORDER_CONFIRMED",
                    "SYSTEM",
                    "Order confirmed via successful payment " + payment.getId()
            ));
        } else if (order.getStatus() == OrderStatus.CANCELLED) {
            // Contradictory outcome: late payment success for a cancelled order!
            // Must NOT resurrect the order or reserve inventory again.
            // Records a reconciliation mismatch and simulated refund-review action.
            log.warn("Late payment success {} for CANCELLED order {}. Recording reconciliation record for refund review.",
                    payment.getId(), order.getId());

            String mismatchKey = "LATE_PAYMENT_CANCELLED_ORDER:" + order.getId();
            Optional<ReconciliationRecord> existing = reconciliationRecordRepository.findByMismatchKey(mismatchKey);
            if (existing.isEmpty()) {
                ReconciliationRecord record = new ReconciliationRecord(
                        mismatchKey,
                        order.getId(),
                        payment.getId(),
                        "PAYMENT_SUCCEEDED_ORDER_CANCELLED",
                        ReconciliationStatus.ACTION_REQUIRED,
                        "{\"action\":\"REFUND_REVIEW_REQUIRED\",\"amount\":" + payment.getAmount() + ",\"orderId\":\"" + order.getId() + "\",\"paymentId\":\"" + payment.getId() + "\"}"
                );
                reconciliationRecordRepository.save(record);

                auditLogRepository.save(new AuditLog(
                        "reconciliation",
                        order.getId().toString(),
                        "MISMATCH_DETECTED",
                        "SYSTEM",
                        "Late payment success for cancelled order: action=REFUND_REVIEW_REQUIRED, paymentId=" + payment.getId()
                ));
            }
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId) {
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null");
        }
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId));
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(UUID orderId, PaymentStatus status) {
        if (orderId != null) {
            return paymentRepository.findByOrderId(orderId).stream().map(PaymentResponse::from).toList();
        }
        if (status != null) {
            return paymentRepository.findByStatusOrderByCreatedAtDesc(status).stream().map(PaymentResponse::from).toList();
        }
        return paymentRepository.findAllByOrderByCreatedAtDesc().stream().map(PaymentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<com.assignment.fulfilment.payments.dto.PaymentCallbackResponse> listCallbacks(UUID paymentId) {
        List<com.assignment.fulfilment.payments.domain.PaymentCallback> list = paymentId != null
                ? paymentCallbackRepository.findByPaymentIdOrderByReceivedAtDesc(paymentId)
                : paymentCallbackRepository.findAllByOrderByReceivedAtDesc();
        return list.stream().map(com.assignment.fulfilment.payments.dto.PaymentCallbackResponse::from).toList();
    }
}
