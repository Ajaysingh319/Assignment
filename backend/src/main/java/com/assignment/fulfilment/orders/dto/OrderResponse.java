package com.assignment.fulfilment.orders.dto;

import com.assignment.fulfilment.inventory.dto.ReservationItemResponse;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        OrderStatus status,
        String idempotencyKey,
        String currency,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        List<ReservationItemResponse> reservations,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant fulfilledAt
) {
}
