package com.assignment.fulfilment.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ReserveInventoryRequest(
        @NotNull(message = "orderId must not be null")
        UUID orderId,
        @NotEmpty(message = "items must not be empty")
        List<@Valid OrderItemRequest> items
) {
}
