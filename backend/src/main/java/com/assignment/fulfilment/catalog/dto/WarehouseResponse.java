package com.assignment.fulfilment.catalog.dto;

import com.assignment.fulfilment.catalog.domain.Warehouse;
import java.time.Instant;
import java.util.UUID;

public record WarehouseResponse(
        UUID id,
        String code,
        String name,
        String location,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static WarehouseResponse from(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getCode(),
                warehouse.getName(),
                warehouse.getLocation(),
                warehouse.isActive(),
                warehouse.getCreatedAt(),
                warehouse.getUpdatedAt()
        );
    }
}
