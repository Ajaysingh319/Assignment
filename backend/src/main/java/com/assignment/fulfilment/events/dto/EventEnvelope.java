package com.assignment.fulfilment.events.dto;

import java.time.Instant;

public record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String payload
) {
}
