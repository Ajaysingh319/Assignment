package com.assignment.fulfilment.inventory.dto;

import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReservationItemResponse(
        UUID id,
        UUID orderId,
        UUID productId,
        UUID warehouseId,
        long quantity,
        ReservationStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant releasedAt,
        Instant fulfilledAt
) {
    public static ReservationItemResponse from(Reservation reservation) {
        return new ReservationItemResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getProductId(),
                reservation.getWarehouseId(),
                reservation.getQuantity(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt(),
                reservation.getReleasedAt(),
                reservation.getFulfilledAt()
        );
    }
}
