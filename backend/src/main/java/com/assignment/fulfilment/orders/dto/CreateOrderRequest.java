package com.assignment.fulfilment.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record CreateOrderRequest(
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO code")
        String currency,

        @NotEmpty(message = "items must not be empty")
        @Valid
        List<CreateOrderItemRequest> items
) {
    public CreateOrderRequest {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        } else {
            currency = currency.trim().toUpperCase();
        }
    }
}
