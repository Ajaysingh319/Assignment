package com.assignment.fulfilment.inventory.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AdjustInventoryRequest(
        @NotNull(message = "must not be null") UUID productId,
        @NotNull(message = "must not be null") UUID warehouseId,
        @NotNull(message = "must not be null") Long availableDelta
) {
}
