package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.common.exception.IdempotencyConflictException;
import com.assignment.fulfilment.common.exception.InvalidOrderStateTransitionException;
import com.assignment.fulfilment.inventory.InventoryReservationService;
import com.assignment.fulfilment.inventory.dto.ReservationItemResponse;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderItemResponse;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderTransactionalWriter orderTransactionalWriter;
    private final InventoryReservationService inventoryReservationService;
    private final AuditLogRepository auditLogRepository;
    private final com.assignment.fulfilment.events.publisher.OutboxService outboxService;

    public OrderService(
            OrderRepository orderRepository,
            OrderTransactionalWriter orderTransactionalWriter,
            InventoryReservationService inventoryReservationService,
            AuditLogRepository auditLogRepository,
            com.assignment.fulfilment.events.publisher.OutboxService outboxService
    ) {
        this.orderRepository = orderRepository;
        this.orderTransactionalWriter = orderTransactionalWriter;
        this.inventoryReservationService = inventoryReservationService;
        this.auditLogRepository = auditLogRepository;
        this.outboxService = outboxService;
    }

    /**
     * Creates an order requiring an idempotency key.
     * Enforces idempotency via PostgreSQL unique constraint:
     * - Same key + same canonical payload -> returns existing order.
     * - Same key + different payload -> throws IdempotencyConflictException (HTTP 409).
     * - Concurrent requests -> database unique constraint prevents duplicates, loser resolves to winner.
     */
    public OrderResponse createOrder(String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Header 'Idempotency-Key' must not be blank");
        }
        if (request == null) {
            throw new IllegalArgumentException("Request body must not be null");
        }

        String requestHash = CanonicalPayloadHasher.computeHash(request);

        // Fast path: check if order with idempotency key already exists
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Order order = existing.get();
            if (order.getRequestHash().equals(requestHash)) {
                return toResponse(order);
            }
            throw new IdempotencyConflictException(
                    idempotencyKey,
                    "Idempotency key '" + idempotencyKey + "' was already used with a different request payload"
            );
        }

        try {
            UUID orderId = UUID.randomUUID();
            return orderTransactionalWriter.createOrderInTransaction(orderId, idempotencyKey, requestHash, request);
        } catch (DataIntegrityViolationException dive) {
            // Unique constraint violation: concurrent request won the race. Resolve to winning order.
            return resolveConcurrentIdempotency(idempotencyKey, requestHash, dive);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(OrderStatus status) {
        List<Order> orders = status != null
                ? orderRepository.findByStatusOrderByCreatedAtDesc(status)
                : orderRepository.findAllByOrderByCreatedAtDesc();
        return orders.stream().map(this::toResponse).toList();
    }

    /**
     * Confirms a PENDING_PAYMENT order.
     * Allowed transition: PENDING_PAYMENT -> CONFIRMED.
     * Safe when repeated (idempotent).
     */
    @Transactional
    public OrderResponse confirmOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        boolean changed = order.confirm();
        if (changed) {
            orderRepository.save(order);
            inventoryReservationService.confirm(orderId);
            auditLogRepository.save(new AuditLog(
                    "order",
                    order.getId().toString(),
                    "ORDER_CONFIRMED",
                    "SYSTEM",
                    "Order confirmed"
            ));
            outboxService.publish(
                    "ORDER",
                    order.getId().toString(),
                    "ORDER_CONFIRMED",
                    1,
                    "{\"orderId\":\"" + order.getId() + "\",\"status\":\"CONFIRMED\"}",
                    null
            );
        }
        return toResponse(order);
    }

    /**
     * Cancels a PENDING_PAYMENT order.
     * Allowed transition: PENDING_PAYMENT -> CANCELLED.
     * Safe when repeated (idempotent): releases reservations at most once.
     */
    @Transactional
    public OrderResponse cancelOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        boolean changed = order.cancel();
        if (changed) {
            orderRepository.save(order);
            inventoryReservationService.cancel(orderId);
            auditLogRepository.save(new AuditLog(
                    "order",
                    order.getId().toString(),
                    "ORDER_CANCELLED",
                    "SYSTEM",
                    "Order cancelled and reservations released"
            ));
            outboxService.publish(
                    "ORDER",
                    order.getId().toString(),
                    "ORDER_CANCELLED",
                    1,
                    "{\"orderId\":\"" + order.getId() + "\",\"status\":\"CANCELLED\"}",
                    null
            );
        }
        return toResponse(order);
    }

    /**
     * Fulfills a CONFIRMED order.
     * Allowed transition: CONFIRMED -> FULFILLED.
     * Safe when repeated (idempotent): moves reservations to fulfilled at most once.
     */
    @Transactional
    public OrderResponse fulfilOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        boolean changed = order.fulfil();
        if (changed) {
            orderRepository.save(order);
            inventoryReservationService.fulfil(orderId);
            auditLogRepository.save(new AuditLog(
                    "order",
                    order.getId().toString(),
                    "ORDER_FULFILLED",
                    "SYSTEM",
                    "Order fulfilled and reservations completed"
            ));
            outboxService.publish(
                    "ORDER",
                    order.getId().toString(),
                    "ORDER_FULFILLED",
                    1,
                    "{\"orderId\":\"" + order.getId() + "\",\"status\":\"FULFILLED\"}",
                    null
            );
        }
        return toResponse(order);
    }

    /**
     * Expires a PENDING_PAYMENT order due to timeout.
     * Allowed transition: PENDING_PAYMENT -> CANCELLED.
     * Safe when repeated (idempotent).
     */
    @Transactional
    public OrderResponse expireOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return toResponse(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateTransitionException(
                    order.getStatus(),
                    OrderStatus.CANCELLED,
                    "Cannot expire order in state " + order.getStatus() + ". Only PENDING_PAYMENT orders can expire."
            );
        }

        order.cancel();
        orderRepository.save(order);
        inventoryReservationService.cancel(orderId);
        auditLogRepository.save(new AuditLog(
                "order",
                order.getId().toString(),
                "ORDER_EXPIRED",
                "SYSTEM",
                "Order expired due to timeout and reservations released"
        ));
        outboxService.publish(
                "ORDER",
                order.getId().toString(),
                "ORDER_EXPIRED",
                1,
                "{\"orderId\":\"" + order.getId() + "\",\"status\":\"CANCELLED\"}",
                null
        );
        return toResponse(order);
    }

    private OrderResponse resolveConcurrentIdempotency(
            String idempotencyKey,
            String requestHash,
            DataIntegrityViolationException originalException
    ) {
        for (int i = 0; i < 5; i++) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Order order = existing.get();
                if (order.getRequestHash().equals(requestHash)) {
                    return toResponse(order);
                }
                throw new IdempotencyConflictException(
                        idempotencyKey,
                        "Idempotency key '" + idempotencyKey + "' was already used with a different request payload"
                );
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw originalException;
            }
        }
        throw originalException;
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getLineTotal()
                ))
                .toList();

        List<ReservationItemResponse> reservations = List.of();
        try {
            reservations = inventoryReservationService.getReservations(order.getId()).items();
        } catch (NoSuchElementException ignored) {
            // Reservations may not exist if order was created without inventory lines or in test fixture
        }

        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getCurrency(),
                order.getTotalAmount(),
                itemResponses,
                reservations,
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getFulfilledAt()
        );
    }
}
