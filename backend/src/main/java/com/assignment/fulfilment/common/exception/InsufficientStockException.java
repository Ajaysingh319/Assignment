package com.assignment.fulfilment.common.exception;

import java.util.UUID;

public class InsufficientStockException extends RuntimeException {

    private final UUID productId;
    private final long requestedQuantity;
    private final long availableQuantity;

    public InsufficientStockException(UUID productId, long requestedQuantity, long availableQuantity) {
        super("Insufficient stock for product %s: requested %d, available %d".formatted(
                productId, requestedQuantity, availableQuantity));
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public UUID getProductId() {
        return productId;
    }

    public long getRequestedQuantity() {
        return requestedQuantity;
    }

    public long getAvailableQuantity() {
        return availableQuantity;
    }
}
