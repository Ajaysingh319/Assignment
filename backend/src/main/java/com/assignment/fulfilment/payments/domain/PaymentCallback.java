package com.assignment.fulfilment.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_callbacks")
public class PaymentCallback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "provider_callback_id", unique = true, nullable = false, length = 64)
    private String providerCallbackId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "received_payload", columnDefinition = "TEXT")
    private String receivedPayload;

    @Column(nullable = false, length = 32)
    private String result;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected PaymentCallback() {
    }

    public PaymentCallback(String providerCallbackId, UUID paymentId, String receivedPayload, String result) {
        if (providerCallbackId == null || providerCallbackId.isBlank()) {
            throw new IllegalArgumentException("providerCallbackId must not be blank");
        }
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null");
        }
        this.providerCallbackId = providerCallbackId;
        this.paymentId = paymentId;
        this.receivedPayload = receivedPayload;
        this.result = result != null ? result : "UNKNOWN";
        this.receivedAt = Instant.now();
        this.processedAt = this.receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getProviderCallbackId() {
        return providerCallbackId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getReceivedPayload() {
        return receivedPayload;
    }

    public String getResult() {
        return result;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
