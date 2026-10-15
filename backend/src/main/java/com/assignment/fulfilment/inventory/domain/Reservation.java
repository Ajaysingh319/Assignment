package com.assignment.fulfilment.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "reservations")
@EntityListeners(AuditingEntityListener.class)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Min(1)
    @Column(nullable = false)
    private long quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReservationStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    protected Reservation() {
    }

    public Reservation(UUID orderId, UUID productId, UUID warehouseId, long quantity, ReservationStatus status) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
        if (warehouseId == null) {
            throw new IllegalArgumentException("warehouseId must not be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.orderId = orderId;
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.status = status != null ? status : ReservationStatus.RESERVED;
    }

    public void confirm() {
        if (this.status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Only RESERVED reservations can be confirmed, current status: " + status);
        }
        this.status = ReservationStatus.CONFIRMED;
        this.updatedAt = Instant.now();
    }

    public void release() {
        if (this.status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Only RESERVED reservations can be released, current status: " + status);
        }
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = Instant.now();
        this.updatedAt = this.releasedAt;
    }

    public void fulfil() {
        if (this.status != ReservationStatus.RESERVED && this.status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("Only RESERVED or CONFIRMED reservations can be fulfilled, current status: " + status);
        }
        this.status = ReservationStatus.FULFILLED;
        this.fulfilledAt = Instant.now();
        this.updatedAt = this.fulfilledAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public long getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }
}
