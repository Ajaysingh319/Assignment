package com.assignment.fulfilment.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "inventory")
@EntityListeners(AuditingEntityListener.class)
public class Inventory {

    @EmbeddedId
    private InventoryId id;

    @Min(0)
    @Column(name = "available_qty", nullable = false)
    private long availableQty;

    @Min(0)
    @Column(name = "reserved_qty", nullable = false)
    private long reservedQty;

    @Min(0)
    @Column(name = "fulfilled_qty", nullable = false)
    private long fulfilledQty;

    @Version
    @Column(nullable = false)
    private long version;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inventory() {
    }

    public Inventory(InventoryId id, long availableQty) {
        this.id = id;
        this.availableQty = availableQty;
        this.reservedQty = 0;
        this.fulfilledQty = 0;
    }

    public void adjustAvailable(long delta) {
        long next = availableQty + delta;
        if (next < 0) {
            throw new IllegalArgumentException("available quantity cannot be negative");
        }
        this.availableQty = next;
    }

    public long trackedTotal() {
        return availableQty + reservedQty + fulfilledQty;
    }

    public InventoryId getId() {
        return id;
    }

    public long getAvailableQty() {
        return availableQty;
    }

    public long getReservedQty() {
        return reservedQty;
    }

    public long getFulfilledQty() {
        return fulfilledQty;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
