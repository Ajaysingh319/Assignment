package com.assignment.fulfilment.events.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * @param failures    number of upcoming matching consumption attempts that will fail (0 disarms)
 * @param eventType   optional filter, e.g. ORDER_CANCELLED
 * @param aggregateId optional filter: only events of this order/payment id
 */
public record FaultInjectionRequest(
        @Min(value = 0, message = "failures must be >= 0")
        @Max(value = 20, message = "failures must be <= 20")
        int failures,
        String eventType,
        String aggregateId
) {
}
