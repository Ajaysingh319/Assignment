package com.assignment.fulfilment.reconciliation;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.inventory.ReservationRepository;
import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import com.assignment.fulfilment.orders.OrderRepository;
import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import com.assignment.fulfilment.payments.PaymentService;
import com.assignment.fulfilment.payments.dto.PaymentResponse;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.payments.PaymentRepository;
import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationRecord;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRecordResponse;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRunResponse;
import com.assignment.fulfilment.reconciliation.dto.ResolveMismatchRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ReconciliationRecordRepository reconciliationRecordRepository;
    private final AuditLogRepository auditLogRepository;
    private final PaymentService paymentService;
    private final OrderService orderService;
    private final int stalePendingMinutes;

    public ReconciliationService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            ReservationRepository reservationRepository,
            ReconciliationRecordRepository reconciliationRecordRepository,
            AuditLogRepository auditLogRepository,
            PaymentService paymentService,
            OrderService orderService,
            @Value("${app.reconciliation-stale-pending-minutes:60}") int stalePendingMinutes
    ) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
        this.auditLogRepository = auditLogRepository;
        this.paymentService = paymentService;
        this.orderService = orderService;
        this.stalePendingMinutes = stalePendingMinutes;
    }

    /**
     * Executes reconciliation detection across orders, payments, and reservations.
     * Idempotent: repeated runs do not duplicate unresolved mismatch records or re-execute actions.
     */
    @Transactional
    public ReconciliationRunResponse runReconciliation() {
        log.info("Starting reconciliation run (stalePendingMinutes={})...", stalePendingMinutes);

        List<Order> allOrders = orderRepository.findAll();
        List<Payment> allPayments = paymentRepository.findAll();
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(stalePendingMinutes));

        Map<UUID, List<Payment>> paymentsByOrder = allPayments.stream()
                .collect(Collectors.groupingBy(Payment::getOrderId));

        int detectedCount = 0;
        int existingCount = 0;
        List<ReconciliationRecord> resultRecords = new ArrayList<>();

        // 1. Check: Payment succeeded but order is cancelled
        for (Payment payment : allPayments) {
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                Order order = orderRepository.findById(payment.getOrderId()).orElse(null);
                if (order != null && order.getStatus() == OrderStatus.CANCELLED) {
                    // The payment flow records this incident inline when the late success arrives; do not
                    // open a second record for the same payment.
                    Optional<ReconciliationRecord> inline = reconciliationRecordRepository
                            .findFirstByMismatchTypeAndPaymentId("PAYMENT_SUCCEEDED_ORDER_CANCELLED", payment.getId());
                    if (inline.isPresent()) {
                        existingCount++;
                        resultRecords.add(inline.get());
                        continue;
                    }
                    String mismatchKey = "PAYMENT_SUCCEEDED_ORDER_CANCELLED:" + order.getId() + ":" + payment.getId();
                    String details = "{\"action\":\"REFUND_REVIEW_REQUIRED\",\"orderId\":\"" + order.getId()
                            + "\",\"paymentId\":\"" + payment.getId() + "\",\"amount\":" + payment.getAmount()
                            + ",\"reason\":\"Payment succeeded but order was cancelled. Requires simulated refund review.\"}";

                    SaveResult res = saveOrUpdateMismatch(
                            mismatchKey, order.getId(), payment.getId(),
                            "PAYMENT_SUCCEEDED_ORDER_CANCELLED", ReconciliationStatus.ACTION_REQUIRED, details
                    );
                    if (res.isNew()) detectedCount++; else existingCount++;
                    resultRecords.add(res.record());
                }
            }
        }

        // 2. Check: Order confirmed or fulfilled but expected successful payment is missing
        for (Order order : allOrders) {
            if (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.FULFILLED) {
                List<Payment> orderPayments = paymentsByOrder.getOrDefault(order.getId(), List.of());
                boolean hasSuccessfulPayment = orderPayments.stream()
                        .anyMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED);

                if (!hasSuccessfulPayment) {
                    String mismatchKey = "MISSING_PAYMENT_CONFIRMED_ORDER:" + order.getId();
                    String details = "{\"action\":\"PAYMENT_AUDIT_REQUIRED\",\"orderId\":\"" + order.getId()
                            + "\",\"orderStatus\":\"" + order.getStatus()
                            + "\",\"reason\":\"Order is " + order.getStatus() + " but no successful payment record exists.\"}";

                    SaveResult res = saveOrUpdateMismatch(
                            mismatchKey, order.getId(), null,
                            "MISSING_PAYMENT_CONFIRMED_ORDER", ReconciliationStatus.ACTION_REQUIRED, details
                    );
                    if (res.isNew()) detectedCount++; else existingCount++;
                    resultRecords.add(res.record());
                }
            }
        }

        // 3. Check: Payment, reservation, and order states are inconsistent
        for (Order order : allOrders) {
            List<Reservation> reservations = reservationRepository.findByOrderId(order.getId());
            if (reservations.isEmpty()) {
                continue;
            }

            Set<ReservationStatus> resStatuses = reservations.stream()
                    .map(Reservation::getStatus)
                    .collect(Collectors.toSet());

            if (order.getStatus() == OrderStatus.CANCELLED) {
                // Inconsistency: Cancelled order has non-RELEASED reservations
                boolean hasNonReleased = resStatuses.stream().anyMatch(s -> s != ReservationStatus.RELEASED);
                if (hasNonReleased) {
                    String mismatchKey = "RESERVATION_STATE_INCONSISTENCY:" + order.getId();
                    String details = "{\"action\":\"INVENTORY_RELEASE_AUDIT_REQUIRED\",\"orderId\":\"" + order.getId()
                            + "\",\"orderStatus\":\"CANCELLED\",\"reservationStatuses\":" + resStatuses + "}";

                    SaveResult res = saveOrUpdateMismatch(
                            mismatchKey, order.getId(), null,
                            "RESERVATION_STATE_INCONSISTENCY", ReconciliationStatus.ACTION_REQUIRED, details
                    );
                    if (res.isNew()) detectedCount++; else existingCount++;
                    resultRecords.add(res.record());
                }
            } else if (order.getStatus() == OrderStatus.FULFILLED) {
                // Inconsistency: Fulfilled order has non-FULFILLED reservations
                boolean hasNonFulfilled = resStatuses.stream().anyMatch(s -> s != ReservationStatus.FULFILLED);
                if (hasNonFulfilled) {
                    String mismatchKey = "RESERVATION_STATE_INCONSISTENCY:" + order.getId();
                    String details = "{\"action\":\"INVENTORY_FULFILMENT_AUDIT_REQUIRED\",\"orderId\":\"" + order.getId()
                            + "\",\"orderStatus\":\"FULFILLED\",\"reservationStatuses\":" + resStatuses + "}";

                    SaveResult res = saveOrUpdateMismatch(
                            mismatchKey, order.getId(), null,
                            "RESERVATION_STATE_INCONSISTENCY", ReconciliationStatus.ACTION_REQUIRED, details
                    );
                    if (res.isNew()) detectedCount++; else existingCount++;
                    resultRecords.add(res.record());
                }
            } else if (order.getStatus() == OrderStatus.CONFIRMED) {
                // Inconsistency: Confirmed order has RELEASED reservations
                boolean hasReleased = resStatuses.contains(ReservationStatus.RELEASED);
                if (hasReleased) {
                    String mismatchKey = "RESERVATION_STATE_INCONSISTENCY:" + order.getId();
                    String details = "{\"action\":\"INVENTORY_CONFIRMATION_AUDIT_REQUIRED\",\"orderId\":\"" + order.getId()
                            + "\",\"orderStatus\":\"CONFIRMED\",\"reservationStatuses\":" + resStatuses + "}";

                    SaveResult res = saveOrUpdateMismatch(
                            mismatchKey, order.getId(), null,
                            "RESERVATION_STATE_INCONSISTENCY", ReconciliationStatus.ACTION_REQUIRED, details
                    );
                    if (res.isNew()) detectedCount++; else existingCount++;
                    resultRecords.add(res.record());
                }
            }
        }

        // 4. Check: Orders or payments remain pending beyond configurable age
        for (Order order : allOrders) {
            if (order.getStatus() == OrderStatus.PENDING_PAYMENT && order.getCreatedAt().isBefore(cutoff)) {
                String mismatchKey = "STALE_PENDING_ORDER:" + order.getId();
                String details = "{\"action\":\"STALE_ORDER_REVIEW_REQUIRED\",\"orderId\":\"" + order.getId()
                        + "\",\"createdAt\":\"" + order.getCreatedAt()
                        + "\",\"reason\":\"Order pending payment beyond " + stalePendingMinutes + " minutes.\"}";

                SaveResult res = saveOrUpdateMismatch(
                        mismatchKey, order.getId(), null,
                        "STALE_PENDING_ORDER", ReconciliationStatus.OPEN, details
                );
                if (res.isNew()) detectedCount++; else existingCount++;
                resultRecords.add(res.record());
            }
        }

        for (Payment payment : allPayments) {
            if ((payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.TIMED_OUT)
                    && payment.getCreatedAt().isBefore(cutoff)) {
                String mismatchKey = "STALE_PENDING_PAYMENT:" + payment.getId();
                String details = "{\"action\":\"STALE_PAYMENT_SYNC_REQUIRED\",\"paymentId\":\"" + payment.getId()
                        + "\",\"status\":\"" + payment.getStatus()
                        + "\",\"createdAt\":\"" + payment.getCreatedAt()
                        + "\",\"reason\":\"Payment in " + payment.getStatus() + " beyond " + stalePendingMinutes + " minutes.\"}";

                SaveResult res = saveOrUpdateMismatch(
                        mismatchKey, payment.getOrderId(), payment.getId(),
                        "STALE_PENDING_PAYMENT", ReconciliationStatus.OPEN, details
                );
                if (res.isNew()) detectedCount++; else existingCount++;
                resultRecords.add(res.record());
            }
        }

        // 5. Check: the same order was charged successfully more than once
        for (Order order : allOrders) {
            List<Payment> succeeded = paymentsByOrder.getOrDefault(order.getId(), List.of()).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                    .toList();
            if (succeeded.size() > 1) {
                String mismatchKey = "DUPLICATE_SUCCESSFUL_PAYMENT:" + order.getId();
                String paymentIds = succeeded.stream().map(p -> "\"" + p.getId() + "\"").collect(Collectors.joining(","));
                String details = "{\"action\":\"REFUND_DUPLICATE_CHARGE\",\"orderId\":\"" + order.getId()
                        + "\",\"successfulPayments\":" + succeeded.size() + ",\"paymentIds\":[" + paymentIds
                        + "],\"reason\":\"Order was charged successfully more than once; refund the extra payment(s).\"}";
                // Reference the most recent charge: it is the one to refund
                Payment latest = succeeded.stream().max(java.util.Comparator.comparing(Payment::getCreatedAt)).orElseThrow();
                SaveResult res = saveOrUpdateMismatch(
                        mismatchKey, order.getId(), latest.getId(),
                        "DUPLICATE_SUCCESSFUL_PAYMENT", ReconciliationStatus.ACTION_REQUIRED, details
                );
                if (res.isNew()) detectedCount++; else existingCount++;
                resultRecords.add(res.record());
            }
        }

        // 6. Check: a successful payment does not match the order total or currency
        Map<UUID, Order> ordersById = allOrders.stream().collect(Collectors.toMap(Order::getId, o -> o));
        for (Payment payment : allPayments) {
            if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
                continue;
            }
            Order order = ordersById.get(payment.getOrderId());
            if (order == null) {
                continue;
            }
            boolean amountDiffers = payment.getAmount().compareTo(order.getTotalAmount()) != 0;
            boolean currencyDiffers = !payment.getCurrency().equalsIgnoreCase(order.getCurrency());
            if (amountDiffers || currencyDiffers) {
                String mismatchKey = "PAYMENT_AMOUNT_MISMATCH:" + payment.getId();
                String details = "{\"action\":\"PAYMENT_AMOUNT_REVIEW_REQUIRED\",\"orderId\":\"" + order.getId()
                        + "\",\"paymentId\":\"" + payment.getId()
                        + "\",\"orderTotal\":" + order.getTotalAmount() + ",\"orderCurrency\":\"" + order.getCurrency()
                        + "\",\"paidAmount\":" + payment.getAmount() + ",\"paidCurrency\":\"" + payment.getCurrency() + "\"}";
                SaveResult res = saveOrUpdateMismatch(
                        mismatchKey, order.getId(), payment.getId(),
                        "PAYMENT_AMOUNT_MISMATCH", ReconciliationStatus.ACTION_REQUIRED, details
                );
                if (res.isNew()) detectedCount++; else existingCount++;
                resultRecords.add(res.record());
            }
        }

        UUID runId = UUID.randomUUID();
        Instant runAt = Instant.now();
        auditLogRepository.save(new AuditLog(
                "reconciliation_run",
                runId.toString(),
                "RECONCILIATION_RUN",
                "SYSTEM",
                "ordersChecked=" + allOrders.size() + ", paymentsChecked=" + allPayments.size()
                        + ", newMismatches=" + detectedCount + ", alreadyKnown=" + existingCount
        ));

        log.info("Reconciliation run {} completed. orders={}, payments={}, detected={}, existing={}",
                runId, allOrders.size(), allPayments.size(), detectedCount, existingCount);
        return new ReconciliationRunResponse(
                detectedCount,
                existingCount,
                resultRecords.stream().map(ReconciliationRecordResponse::from).toList(),
                runId,
                runAt,
                allOrders.size(),
                allPayments.size()
        );
    }

    private record SaveResult(ReconciliationRecord record, boolean isNew) {}

    private SaveResult saveOrUpdateMismatch(
            String mismatchKey,
            UUID orderId,
            UUID paymentId,
            String mismatchType,
            ReconciliationStatus status,
            String details
    ) {
        Optional<ReconciliationRecord> existing = reconciliationRecordRepository.findByMismatchKey(mismatchKey);
        if (existing.isPresent()) {
            ReconciliationRecord record = existing.get();
            // Do not alter or reopen records that have already been resolved or dismissed
            return new SaveResult(record, false);
        }

        ReconciliationRecord record = new ReconciliationRecord(
                mismatchKey, orderId, paymentId, mismatchType, status, details
        );
        ReconciliationRecord saved = reconciliationRecordRepository.save(record);

        auditLogRepository.save(new AuditLog(
                "reconciliation",
                saved.getId().toString(),
                "MISMATCH_DETECTED",
                "SYSTEM",
                "mismatchKey=" + mismatchKey + ", type=" + mismatchType + ", status=" + status
        ));

        return new SaveResult(saved, true);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRecordResponse> listRecords(ReconciliationStatus status, String mismatchType) {
        List<ReconciliationRecord> records;
        if (status != null) {
            records = reconciliationRecordRepository.findByStatusOrderByDetectedAtDesc(status);
        } else {
            records = reconciliationRecordRepository.findAllByOrderByDetectedAtDesc();
        }

        if (mismatchType != null && !mismatchType.isBlank()) {
            records = records.stream()
                    .filter(r -> r.getMismatchType().equalsIgnoreCase(mismatchType.trim()))
                    .toList();
        }

        return records.stream().map(ReconciliationRecordResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReconciliationRecordResponse getRecord(UUID recordId) {
        if (recordId == null) {
            throw new IllegalArgumentException("recordId must not be null");
        }
        ReconciliationRecord record = reconciliationRecordRepository.findById(recordId)
                .orElseThrow(() -> new NoSuchElementException("Reconciliation record not found: " + recordId));
        return ReconciliationRecordResponse.from(record);
    }

    /**
     * Resolves or dismisses a reconciliation mismatch with audited corrective action history.
     * Idempotent: repeated resolve calls on already resolved records return existing record.
     */
    @Transactional
    public ReconciliationRecordResponse resolveRecord(UUID recordId, ResolveMismatchRequest request) {
        if (recordId == null) {
            throw new IllegalArgumentException("recordId must not be null");
        }
        ReconciliationRecord record = reconciliationRecordRepository.findById(recordId)
                .orElseThrow(() -> new NoSuchElementException("Reconciliation record not found: " + recordId));

        String action = request != null && request.action() != null ? request.action().toUpperCase() : "RESOLVE";

        if (record.getStatus() == ReconciliationStatus.RESOLVED || record.getStatus() == ReconciliationStatus.DISMISSED) {
            log.info("Reconciliation record {} is already {}. Safe idempotent return.", recordId, record.getStatus());
            return ReconciliationRecordResponse.from(record);
        }

        String notes = request != null && request.notes() != null && !request.notes().isBlank()
                ? request.notes() : "Simulated action resolved";

        // Corrective actions change real business state; each is validated against the record type
        // and executed through the owning module so inventory and order invariants are preserved.
        String effect;
        switch (action) {
            case "REFUND_PAYMENT" -> {
                if (record.getPaymentId() == null) {
                    throw new IllegalArgumentException("REFUND_PAYMENT requires a mismatch that references a payment");
                }
                if (!Set.of("PAYMENT_SUCCEEDED_ORDER_CANCELLED", "DUPLICATE_SUCCESSFUL_PAYMENT", "PAYMENT_AMOUNT_MISMATCH")
                        .contains(record.getMismatchType())) {
                    throw new IllegalArgumentException("REFUND_PAYMENT is not applicable to mismatch type " + record.getMismatchType());
                }
                PaymentResponse refunded = paymentService.refundPayment(record.getPaymentId(), "reconciliation " + record.getMismatchKey());
                effect = "payment " + refunded.id() + " -> " + refunded.status();
                record.resolve();
            }
            case "EXPIRE_ORDER" -> {
                if (record.getOrderId() == null || !"STALE_PENDING_ORDER".equals(record.getMismatchType())) {
                    throw new IllegalArgumentException("EXPIRE_ORDER is only applicable to STALE_PENDING_ORDER mismatches");
                }
                OrderResponse expired = orderService.expireOrder(record.getOrderId());
                effect = "order " + expired.id() + " -> " + expired.status() + ", reservations released";
                record.resolve();
            }
            case "DISMISS" -> {
                effect = "no business state changed";
                record.dismiss();
            }
            case "RESOLVE" -> {
                effect = "marked resolved; no business state changed";
                record.resolve();
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported action '" + action + "'. Use RESOLVE, DISMISS, REFUND_PAYMENT or EXPIRE_ORDER");
        }
        record.recordResolution(action, notes + " [" + effect + "]");
        ReconciliationRecord saved = reconciliationRecordRepository.save(record);

        auditLogRepository.save(new AuditLog(
                "reconciliation",
                saved.getId().toString(),
                "MISMATCH_" + saved.getStatus(),
                "OPERATOR",
                "Action=" + action + ", status=" + saved.getStatus() + ", effect=" + effect
                        + ", notes=" + notes + ", mismatchKey=" + saved.getMismatchKey()
        ));

        return ReconciliationRecordResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRecordResponse> recordsForOrder(UUID orderId) {
        return reconciliationRecordRepository.findByOrderIdOrderByDetectedAtAsc(orderId).stream()
                .map(ReconciliationRecordResponse::from)
                .toList();
    }
}
