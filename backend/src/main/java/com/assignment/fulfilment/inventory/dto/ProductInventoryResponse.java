package com.assignment.fulfilment.inventory.dto;

import java.util.List;
import java.util.UUID;

public record ProductInventoryResponse(
        UUID productId,
        String sku,
        String productName,
        List<WarehouseInventoryResponse> warehouses
) {
}
