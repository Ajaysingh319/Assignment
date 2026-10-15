package com.assignment.fulfilment.orders.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID id,
        UUID productId,
        long quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
