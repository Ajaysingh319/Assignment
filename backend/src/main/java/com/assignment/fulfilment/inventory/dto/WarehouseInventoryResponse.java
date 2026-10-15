package com.assignment.fulfilment.inventory.dto;

import java.time.Instant;
import java.util.UUID;

public record WarehouseInventoryResponse(
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        long availableQty,
        long reservedQty,
        long fulfilledQty,
        long trackedTotal,
        Instant updatedAt
) {
}
