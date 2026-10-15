package com.assignment.fulfilment.payments.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record PaymentCallbackRequest(
        @NotBlank(message = "callbackId must not be blank")
        String callbackId,

        UUID paymentId,

        UUID orderId,

        String providerReference,

        @JsonAlias("status")
        @NotBlank(message = "result must not be blank")
        @Pattern(regexp = "^(SUCCEEDED|FAILED|TIMED_OUT)$", message = "result must be SUCCEEDED, FAILED, or TIMED_OUT")
        String result
) {
    public PaymentCallbackRequest(String callbackId, UUID paymentId, String providerReference, String result) {
        this(callbackId, paymentId, null, providerReference, result);
    }
}


