package com.assignment.fulfilment.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OrderItemRequest(
        @NotNull(message = "productId must not be null")
        UUID productId,
        @Min(value = 1, message = "quantity must be greater than zero")
        long quantity
) {
}
