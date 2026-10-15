package com.assignment.fulfilment.catalog.dto;

import com.assignment.fulfilment.catalog.domain.Product;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
