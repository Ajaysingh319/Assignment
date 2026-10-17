package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.orders.domain.OrderRequest;
import com.assignment.fulfilment.orders.domain.OrderRequestOutcome;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the outcome of every order-creation request.
 * ACCEPTED is written inside the order's own transaction (atomic with the order); rejections and replays are
 * written in a separate transaction after the business transaction rolled back or short-circuited.
 */
@Component
public class OrderRequestRecorder {

    private static final Logger log = LoggerFactory.getLogger(OrderRequestRecorder.class);

    private final OrderRequestRepository orderRequestRepository;

    public OrderRequestRecorder(OrderRequestRepository orderRequestRepository) {
        this.orderRequestRepository = orderRequestRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAccepted(String idempotencyKey, UUID orderId, CreateOrderRequest request) {
        orderRequestRepository.save(build(idempotencyKey, OrderRequestOutcome.ACCEPTED, orderId, null, request));
    }

    /**
     * Never lets a recording problem mask the real outcome of the request.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String idempotencyKey, OrderRequestOutcome outcome, UUID orderId, String reason, CreateOrderRequest request) {
        try {
            orderRequestRepository.save(build(idempotencyKey, outcome, orderId, reason, request));
        } catch (RuntimeException e) {
            log.warn("Could not record order request outcome {} for key {}: {}", outcome, idempotencyKey, e.getMessage());
        }
    }

    private static OrderRequest build(String key, OrderRequestOutcome outcome, UUID orderId, String reason, CreateOrderRequest request) {
        List<CreateOrderItemRequest> items = request != null && request.items() != null ? request.items() : List.of();
        long totalQuantity = items.stream().mapToLong(i -> i.quantity() != null ? i.quantity() : 0L).sum();
        String summary = items.stream()
                .map(i -> i.productId() + "x" + i.quantity())
                .collect(Collectors.joining(","));
        String trimmedReason = reason != null && reason.length() > 1000 ? reason.substring(0, 1000) : reason;
        return new OrderRequest(key, outcome, orderId, trimmedReason, items.size(), totalQuantity, summary);
    }
}
