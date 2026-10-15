package com.assignment.fulfilment.payments.dto;

import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        String providerReference,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String lastCallbackId,
        Instant createdAt,
        Instant updatedAt,
        Instant succeededAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getProviderReference(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getLastCallbackId(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getSucceededAt()
        );
    }
}
