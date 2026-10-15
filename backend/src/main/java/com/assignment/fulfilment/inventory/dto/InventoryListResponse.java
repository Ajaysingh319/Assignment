package com.assignment.fulfilment.inventory.dto;

import java.util.List;

public record InventoryListResponse(List<ProductInventoryResponse> items) {
}
