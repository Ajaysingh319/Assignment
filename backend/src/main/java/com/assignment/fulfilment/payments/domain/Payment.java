package com.assignment.fulfilment.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "provider_reference", unique = true, length = 64)
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "last_callback_id", length = 64)
    private String lastCallbackId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    protected Payment() {
    }

    public Payment(UUID orderId, String providerReference, PaymentStatus status, BigDecimal amount, String currency) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        this.orderId = orderId;
        this.providerReference = providerReference;
        this.status = status != null ? status : PaymentStatus.PENDING;
        this.amount = amount;
        this.currency = currency != null ? currency : "USD";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        if (this.status == PaymentStatus.SUCCEEDED) {
            this.succeededAt = this.createdAt;
        }
    }

    public void markSucceeded(String callbackId) {
        this.status = PaymentStatus.SUCCEEDED;
        this.succeededAt = Instant.now();
        this.updatedAt = this.succeededAt;
        if (callbackId != null) {
            this.lastCallbackId = callbackId;
        }
    }

    public void markFailed(String callbackId) {
        this.status = PaymentStatus.FAILED;
        this.updatedAt = Instant.now();
        if (callbackId != null) {
            this.lastCallbackId = callbackId;
        }
    }

    public void markTimedOut() {
        this.status = PaymentStatus.TIMED_OUT;
        this.updatedAt = Instant.now();
    }

    public void recordCallbackId(String callbackId) {
        this.lastCallbackId = callbackId;
        this.updatedAt = Instant.now();
    }

    public void markRefunded() {
        if (this.status != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException("Only SUCCEEDED payments can be refunded, current status: " + status);
        }
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = Instant.now();
    }

    public boolean isFinal() {
        return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.FAILED || status == PaymentStatus.REFUNDED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getLastCallbackId() {
        return lastCallbackId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getSucceededAt() {
        return succeededAt;
    }
}
