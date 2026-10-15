package com.assignment.fulfilment.common.api;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        List<String> details
) {
}
