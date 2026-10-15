package com.assignment.fulfilment.inventory.dto;

import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID orderId,
        String status,
        List<ReservationItemResponse> items
) {
}
