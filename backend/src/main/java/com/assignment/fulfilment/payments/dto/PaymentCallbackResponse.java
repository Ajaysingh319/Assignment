package com.assignment.fulfilment.payments.dto;

import com.assignment.fulfilment.payments.domain.PaymentCallback;
import java.time.Instant;
import java.util.UUID;

public record PaymentCallbackResponse(
        UUID id,
        String providerCallbackId,
        UUID paymentId,
        String result,
        String receivedPayload,
        Instant receivedAt,
        Instant processedAt
) {
    public static PaymentCallbackResponse from(PaymentCallback callback) {
        return new PaymentCallbackResponse(
                callback.getId(),
                callback.getProviderCallbackId(),
                callback.getPaymentId(),
                callback.getResult(),
                callback.getReceivedPayload(),
                callback.getReceivedAt(),
                callback.getProcessedAt()
        );
    }
}
