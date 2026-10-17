package com.assignment.fulfilment.orders.dto;

import com.assignment.fulfilment.orders.domain.OrderRequest;
import com.assignment.fulfilment.orders.domain.OrderRequestOutcome;
import java.time.Instant;
import java.util.UUID;

public record OrderRequestResponse(
        UUID id,
        String idempotencyKey,
        OrderRequestOutcome outcome,
        UUID orderId,
        String reason,
        int itemCount,
        long totalQuantity,
        String requestSummary,
        Instant createdAt
) {
    public static OrderRequestResponse from(OrderRequest r) {
        return new OrderRequestResponse(
                r.getId(), r.getIdempotencyKey(), r.getOutcome(), r.getOrderId(), r.getReason(),
                r.getItemCount(), r.getTotalQuantity(), r.getRequestSummary(), r.getCreatedAt()
        );
    }
}
