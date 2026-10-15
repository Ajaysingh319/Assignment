package com.assignment.fulfilment.orders.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderItemRequest(
        @NotNull(message = "productId must not be null")
        UUID productId,

        @NotNull(message = "quantity must not be null")
        @Min(value = 1, message = "quantity must be at least 1")
        Long quantity,

        BigDecimal unitPrice
) {
}
