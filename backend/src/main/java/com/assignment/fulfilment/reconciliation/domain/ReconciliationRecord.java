package com.assignment.fulfilment.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_records")
public class ReconciliationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "mismatch_key", unique = true, nullable = false, length = 128)
    private String mismatchKey;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "mismatch_type", nullable = false, length = 64)
    private String mismatchType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReconciliationStatus status;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationRecord() {
    }

    public ReconciliationRecord(
            String mismatchKey,
            UUID orderId,
            UUID paymentId,
            String mismatchType,
            ReconciliationStatus status,
            String details
    ) {
        if (mismatchKey == null || mismatchKey.isBlank()) {
            throw new IllegalArgumentException("mismatchKey must not be blank");
        }
        if (mismatchType == null || mismatchType.isBlank()) {
            throw new IllegalArgumentException("mismatchType must not be blank");
        }
        this.mismatchKey = mismatchKey;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.mismatchType = mismatchType;
        this.status = status != null ? status : ReconciliationStatus.ACTION_REQUIRED;
        this.details = details;
        this.detectedAt = Instant.now();
        this.updatedAt = this.detectedAt;
    }

    public void resolve() {
        this.status = ReconciliationStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
    }

    public void dismiss() {
        this.status = ReconciliationStatus.DISMISSED;
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getMismatchKey() {
        return mismatchKey;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getMismatchType() {
        return mismatchType;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public String getDetails() {
        return details;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
