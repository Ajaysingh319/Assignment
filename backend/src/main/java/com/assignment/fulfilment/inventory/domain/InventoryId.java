package com.assignment.fulfilment.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class InventoryId implements Serializable {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    protected InventoryId() {
    }

    public InventoryId(UUID productId, UUID warehouseId) {
        this.productId = productId;
        this.warehouseId = warehouseId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InventoryId that)) {
            return false;
        }
        return Objects.equals(productId, that.productId) && Objects.equals(warehouseId, that.warehouseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, warehouseId);
    }
}
