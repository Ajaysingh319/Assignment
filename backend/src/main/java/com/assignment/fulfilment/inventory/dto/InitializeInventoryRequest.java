package com.assignment.fulfilment.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InitializeInventoryRequest(
        @NotNull(message = "must not be null")
        UUID productId,
        @NotNull(message = "must not be null")
        UUID warehouseId,
        @Min(value = 0, message = "initialStock cannot be negative")
        Long initialStock
) {
}
