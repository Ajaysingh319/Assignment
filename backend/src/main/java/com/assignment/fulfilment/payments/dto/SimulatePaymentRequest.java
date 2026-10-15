package com.assignment.fulfilment.payments.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record SimulatePaymentRequest(
        @JsonAlias("status")
        @NotNull(message = "outcome must not be null")
        @Pattern(regexp = "^(SUCCEEDED|FAILED|TIMED_OUT)$", message = "outcome must be SUCCEEDED, FAILED, or TIMED_OUT")
        String outcome,

        BigDecimal amount,
        String currency
) {
}
