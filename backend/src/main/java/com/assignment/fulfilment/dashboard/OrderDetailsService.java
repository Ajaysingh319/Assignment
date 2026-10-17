package com.assignment.fulfilment.dashboard;

import com.assignment.fulfilment.catalog.ProductRepository;
import com.assignment.fulfilment.catalog.WarehouseRepository;
import com.assignment.fulfilment.catalog.domain.Product;
import com.assignment.fulfilment.catalog.domain.Warehouse;
import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.dashboard.dto.AuditEntryResponse;
import com.assignment.fulfilment.dashboard.dto.OrderDetailsResponse;
import com.assignment.fulfilment.events.EventService;
import com.assignment.fulfilment.events.dto.OutboxEventResponse;
import com.assignment.fulfilment.inventory.ReservationRepository;
import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import com.assignment.fulfilment.orders.OrderRepository;
import com.assignment.fulfilment.orders.OrderRequestRepository;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.dto.OrderRequestResponse;
import com.assignment.fulfilment.payments.PaymentCallbackRepository;
import com.assignment.fulfilment.payments.PaymentRepository;
import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import com.assignment.fulfilment.payments.dto.PaymentCallbackResponse;
import com.assignment.fulfilment.payments.dto.PaymentResponse;
import com.assignment.fulfilment.reconciliation.ReconciliationService;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRecordResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregation of one order across all modules. It only reads persisted state; nothing is derived
 * or invented on the client.
 */
@Service
public class OrderDetailsService {

    private static final Map<String, String> ORDER_ACTION_TO_STATUS = Map.of(
            "ORDER_CREATED", "PENDING_PAYMENT",
            "ORDER_CONFIRMED", "CONFIRMED",
            "ORDER_CANCELLED", "CANCELLED",
            "ORDER_EXPIRED", "CANCELLED",
            "ORDER_FULFILLED", "FULFILLED"
    );

    private final OrderRepository orderRepository;
    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentCallbackRepository paymentCallbackRepository;
    private final OrderRequestRepository orderRequestRepository;
    private final AuditLogRepository auditLogRepository;
    private final EventService eventService;
    private final ReconciliationService reconciliationService;

    public OrderDetailsService(
            OrderRepository orderRepository,
            ReservationRepository reservationRepository,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository,
            PaymentRepository paymentRepository,
            PaymentCallbackRepository paymentCallbackRepository,
            OrderRequestRepository orderRequestRepository,
            AuditLogRepository auditLogRepository,
            EventService eventService,
            ReconciliationService reconciliationService
    ) {
        this.orderRepository = orderRepository;
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.paymentRepository = paymentRepository;
        this.paymentCallbackRepository = paymentCallbackRepository;
        this.orderRequestRepository = orderRequestRepository;
        this.auditLogRepository = auditLogRepository;
        this.eventService = eventService;
        this.reconciliationService = reconciliationService;
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getDetails(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        List<Reservation> reservations = reservationRepository.findByOrderId(orderId).stream()
                .sorted(Comparator.comparing(Reservation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Set<UUID> productIds = new LinkedHashSet<>();
        order.getItems().forEach(i -> productIds.add(i.getProductId()));
        reservations.forEach(r -> productIds.add(r.getProductId()));
        Map<UUID, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, Warehouse> warehouses = warehouseRepository.findAllById(
                        reservations.stream().map(Reservation::getWarehouseId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));

        List<OrderDetailsResponse.Line> lines = order.getItems().stream()
                .map(i -> {
                    Product p = products.get(i.getProductId());
                    return new OrderDetailsResponse.Line(
                            i.getProductId(), p != null ? p.getSku() : null, p != null ? p.getName() : null,
                            i.getQuantity(), i.getUnitPrice(), i.getLineTotal());
                })
                .toList();

        List<OrderDetailsResponse.ReservationLine> reservationLines = reservations.stream()
                .map(r -> {
                    Product p = products.get(r.getProductId());
                    Warehouse w = warehouses.get(r.getWarehouseId());
                    return new OrderDetailsResponse.ReservationLine(
                            r.getId(), r.getProductId(), p != null ? p.getSku() : null,
                            r.getWarehouseId(), w != null ? w.getCode() : null, w != null ? w.getName() : null,
                            r.getQuantity(), r.getStatus().name(), r.getCreatedAt(), r.getUpdatedAt(),
                            r.getReleasedAt(), r.getFulfilledAt());
                })
                .toList();

        List<Payment> payments = paymentRepository.findByOrderId(orderId).stream()
                .sorted(Comparator.comparing(Payment::getCreatedAt))
                .toList();
        List<PaymentCallbackResponse> callbacks = new ArrayList<>();
        for (Payment payment : payments) {
            paymentCallbackRepository.findByPaymentIdOrderByReceivedAtDesc(payment.getId()).stream()
                    .map(PaymentCallbackResponse::from)
                    .forEach(callbacks::add);
        }
        callbacks.sort(Comparator.comparing(PaymentCallbackResponse::receivedAt));

        List<String> aggregateIds = new ArrayList<>();
        aggregateIds.add(orderId.toString());
        payments.forEach(p -> aggregateIds.add(p.getId().toString()));
        List<OutboxEventResponse> events = eventService.eventsForAggregates(aggregateIds);

        List<ReconciliationRecordResponse> reconciliation = reconciliationService.recordsForOrder(orderId);

        List<OrderRequestResponse> requests = orderRequestRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .map(OrderRequestResponse::from)
                .toList();

        Set<String> auditEntityIds = new LinkedHashSet<>(aggregateIds);
        reservations.forEach(r -> auditEntityIds.add(r.getId().toString()));
        reconciliation.forEach(r -> auditEntityIds.add(r.id().toString()));
        List<AuditLog> audit = auditLogRepository.findByEntityIdInOrderByCreatedAtAsc(auditEntityIds);

        List<OrderDetailsResponse.StatusChange> history = audit.stream()
                .filter(a -> "order".equals(a.getEntityType()) && ORDER_ACTION_TO_STATUS.containsKey(a.getAction()))
                .map(a -> new OrderDetailsResponse.StatusChange(
                        a.getCreatedAt(), ORDER_ACTION_TO_STATUS.get(a.getAction()), a.getAction(), a.getActor(), a.getDetails()))
                .toList();

        return new OrderDetailsResponse(
                order.getId(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getCurrency(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getFulfilledAt(),
                aggregateReservationStatus(reservations),
                aggregatePaymentStatus(payments),
                lines,
                reservationLines,
                history,
                payments.stream().map(PaymentResponse::from).toList(),
                callbacks,
                events,
                reconciliation,
                requests,
                audit.stream().map(AuditEntryResponse::from).toList()
        );
    }

    static String aggregateReservationStatus(List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return "NONE";
        }
        Set<ReservationStatus> statuses = reservations.stream().map(Reservation::getStatus).collect(Collectors.toSet());
        return statuses.size() == 1 ? statuses.iterator().next().name() : "MIXED";
    }

    /**
     * SUCCEEDED wins (a paid order is paid even if earlier attempts failed); otherwise the latest attempt.
     */
    static String aggregatePaymentStatus(List<Payment> payments) {
        if (payments.isEmpty()) {
            return "NONE";
        }
        long succeeded = payments.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED).count();
        if (succeeded > 1) {
            return "SUCCEEDED_MULTIPLE";
        }
        if (succeeded == 1) {
            return "SUCCEEDED";
        }
        return payments.get(payments.size() - 1).getStatus().name();
    }
}
