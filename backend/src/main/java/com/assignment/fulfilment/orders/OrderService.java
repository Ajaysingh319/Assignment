package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.common.exception.IdempotencyConflictException;
import com.assignment.fulfilment.common.exception.InsufficientStockException;
import com.assignment.fulfilment.common.exception.InvalidOrderStateTransitionException;
import com.assignment.fulfilment.inventory.InventoryReservationService;
import com.assignment.fulfilment.inventory.dto.ReservationItemResponse;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderRequestOutcome;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderItemResponse;
import com.assignment.fulfilment.orders.dto.OrderRequestResponse;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderTransactionalWriter orderTransactionalWriter;
    private final InventoryReservationService inventoryReservationService;
    private final AuditLogRepository auditLogRepository;
    private final com.assignment.fulfilment.events.publisher.OutboxService outboxService;
    private final OrderRequestRecorder orderRequestRecorder;
    private final OrderRequestRepository orderRequestRepository;

    public OrderService(
            OrderRepository orderRepository,
            OrderTransactionalWriter orderTransactionalWriter,
            InventoryReservationService inventoryReservationService,
            AuditLogRepository auditLogRepository,
            com.assignment.fulfilment.events.publisher.OutboxService outboxService,
            OrderRequestRecorder orderRequestRecorder,
            OrderRequestRepository orderRequestRepository
    ) {
        this.orderRepository = orderRepository;
        this.orderTransactionalWriter = orderTransactionalWriter;
        this.inventoryReservationService = inventoryReservationService;
        this.auditLogRepository = auditLogRepository;
        this.outboxService = outboxService;
        this.orderRequestRecorder = orderRequestRecorder;
        this.orderRequestRepository = orderRequestRepository;
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
            return replayOrConflict(existing.get(), idempotencyKey, requestHash, request);
        }

        try {
            UUID orderId = UUID.randomUUID();
            return orderTransactionalWriter.createOrderInTransaction(orderId, idempotencyKey, requestHash, request);
        } catch (DataIntegrityViolationException dive) {
            // Unique constraint violation: concurrent request won the race. Resolve to winning order.
            return resolveConcurrentIdempotency(idempotencyKey, requestHash, request, dive);
        } catch (InsufficientStockException e) {
            orderRequestRecorder.recordOutcome(idempotencyKey, OrderRequestOutcome.REJECTED_INSUFFICIENT_STOCK, null, e.getMessage(), request);
            throw e;
        } catch (NoSuchElementException | IllegalArgumentException e) {
            orderRequestRecorder.recordOutcome(idempotencyKey, OrderRequestOutcome.REJECTED_INVALID, null, e.getMessage(), request);
            throw e;
        }
    }

    private OrderResponse replayOrConflict(Order order, String idempotencyKey, String requestHash, CreateOrderRequest request) {
        if (order.getRequestHash().equals(requestHash)) {
            orderRequestRecorder.recordOutcome(idempotencyKey, OrderRequestOutcome.IDEMPOTENT_REPLAY, order.getId(),
                    "Existing order returned; no new order or reservation created", request);
            return toResponse(order);
        }
        String message = "Idempotency key '" + idempotencyKey + "' was already used with a different request payload";
        orderRequestRecorder.recordOutcome(idempotencyKey, OrderRequestOutcome.REJECTED_IDEMPOTENCY_CONFLICT, order.getId(), message, request);
        throw new IdempotencyConflictException(idempotencyKey, message);
    }

    @Transactional(readOnly = true)
    public List<OrderRequestResponse> listRequests(OrderRequestOutcome outcome, String keyPrefix, int limit) {
        int size = Math.max(1, Math.min(limit, 1000));
        return orderRequestRepository.search(outcome, toLikePattern(keyPrefix), PageRequest.of(0, size)).stream()
                .map(OrderRequestResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> requestOutcomeCounts(String keyPrefix) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OrderRequestOutcome outcome : OrderRequestOutcome.values()) {
            counts.put(outcome.name(), 0L);
        }
        long total = 0;
        for (Object[] row : orderRequestRepository.countByOutcome(toLikePattern(keyPrefix))) {
            long count = ((Number) row[1]).longValue();
            counts.put(((OrderRequestOutcome) row[0]).name(), count);
            total += count;
        }
        counts.put("TOTAL", total);
        return counts;
    }

    private static String toLikePattern(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return null;
        }
        String escaped = keyPrefix.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return escaped + "%";
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpirableOrderIds(Instant createdBefore) {
        return orderRepository.findIdsByStatusAndCreatedAtBefore(OrderStatus.PENDING_PAYMENT, createdBefore);
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
            CreateOrderRequest request,
            DataIntegrityViolationException originalException
    ) {
        for (int i = 0; i < 5; i++) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), idempotencyKey, requestHash, request);
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
