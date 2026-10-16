package com.assignment.fulfilment.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProductRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,
        @Size(max = 4000, message = "must be at most 4000 characters")
        String description,
        Boolean active
) {
}
