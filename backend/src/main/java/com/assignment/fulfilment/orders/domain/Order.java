package com.assignment.fulfilment.orders.domain;

import com.assignment.fulfilment.common.exception.InvalidOrderStateTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItem> items = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    protected Order() {
    }

    public Order(UUID id, String idempotencyKey, String requestHash, String currency, BigDecimal totalAmount) {
        if (id == null) {
            throw new IllegalArgumentException("Order id must not be null");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("Request hash must not be blank");
        }
        this.id = id;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.currency = currency != null ? currency : "USD";
        this.totalAmount = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    /**
     * Transitions status from PENDING_PAYMENT to CONFIRMED.
     * Safe when repeated (idempotent).
     */
    public boolean confirm() {
        if (this.status == OrderStatus.CONFIRMED) {
            return false;
        }
        if (this.status != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateTransitionException(
                    this.status,
                    OrderStatus.CONFIRMED,
                    "Cannot confirm order in state " + this.status + ". Allowed transition is PENDING_PAYMENT -> CONFIRMED."
            );
        }
        this.status = OrderStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
        this.updatedAt = this.confirmedAt;
        return true;
    }

    /**
     * Transitions status from PENDING_PAYMENT to CANCELLED.
     * Safe when repeated (idempotent).
     */
    public boolean cancel() {
        if (this.status == OrderStatus.CANCELLED) {
            return false;
        }
        if (this.status != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateTransitionException(
                    this.status,
                    OrderStatus.CANCELLED,
                    "Cannot cancel order in state " + this.status + ". Allowed transition is PENDING_PAYMENT -> CANCELLED."
            );
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.updatedAt = this.cancelledAt;
        return true;
    }

    /**
     * Transitions status from CONFIRMED to FULFILLED.
     * Safe when repeated (idempotent).
     */
    public boolean fulfil() {
        if (this.status == OrderStatus.FULFILLED) {
            return false;
        }
        if (this.status != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateTransitionException(
                    this.status,
                    OrderStatus.FULFILLED,
                    "Cannot fulfil order in state " + this.status + ". Allowed transition is CONFIRMED -> FULFILLED."
            );
        }
        this.status = OrderStatus.FULFILLED;
        this.fulfilledAt = Instant.now();
        this.updatedAt = this.fulfilledAt;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }
}
