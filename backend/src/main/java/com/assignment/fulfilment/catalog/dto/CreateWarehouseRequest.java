package com.assignment.fulfilment.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWarehouseRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String code,
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,
        @Size(max = 255, message = "must be at most 255 characters")
        String location,
        Boolean active
) {
}
