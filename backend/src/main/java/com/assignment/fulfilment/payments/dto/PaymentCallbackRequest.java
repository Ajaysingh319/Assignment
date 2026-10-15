package com.assignment.fulfilment.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record PaymentCallbackRequest(
        @NotBlank(message = "callbackId must not be blank")
        String callbackId,

        @NotNull(message = "paymentId must not be null")
        UUID paymentId,

        String providerReference,

        @NotBlank(message = "result must not be blank")
        @Pattern(regexp = "^(SUCCEEDED|FAILED|TIMED_OUT)$", message = "result must be SUCCEEDED, FAILED, or TIMED_OUT")
        String result
) {
}
