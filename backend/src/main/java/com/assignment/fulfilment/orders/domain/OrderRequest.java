package com.assignment.fulfilment.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of one order-creation request and its outcome. Rejected requests do not create orders;
 * they are only visible here, which keeps the orders table limited to real orders.
 */
@Entity
@Table(name = "order_requests")
public class OrderRequest {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderRequestOutcome outcome;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "total_quantity", nullable = false)
    private long totalQuantity;

    @Column(name = "request_summary", columnDefinition = "TEXT")
    private String requestSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderRequest() {
    }

    public OrderRequest(
            String idempotencyKey,
            OrderRequestOutcome outcome,
            UUID orderId,
            String reason,
            int itemCount,
            long totalQuantity,
            String requestSummary
    ) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.outcome = outcome;
        this.orderId = orderId;
        this.reason = reason;
        this.itemCount = itemCount;
        this.totalQuantity = totalQuantity;
        this.requestSummary = requestSummary;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public OrderRequestOutcome getOutcome() {
        return outcome;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getReason() {
        return reason;
    }

    public int getItemCount() {
        return itemCount;
    }

    public long getTotalQuantity() {
        return totalQuantity;
    }

    public String getRequestSummary() {
        return requestSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
