package com.assignment.fulfilment.events.dto;

import com.assignment.fulfilment.events.domain.EventFailure;
import java.time.Instant;
import java.util.UUID;

public record EventFailureResponse(
        UUID id,
        String eventId,
        String topic,
        Integer partitionNumber,
        Long offsetNumber,
        String consumerName,
        int attemptCount,
        String lastError,
        String status,
        String payload,
        Instant firstFailedAt,
        Instant lastFailedAt,
        Instant resolvedAt
) {
    public static EventFailureResponse from(EventFailure failure) {
        return new EventFailureResponse(
                failure.getId(),
                failure.getEventId(),
                failure.getTopic(),
                failure.getPartitionNumber(),
                failure.getOffsetNumber(),
                failure.getConsumerName(),
                failure.getAttemptCount(),
                failure.getLastError(),
                failure.getStatus().name(),
                failure.getPayload(),
                failure.getFirstFailedAt(),
                failure.getLastFailedAt(),
                failure.getResolvedAt()
        );
    }
}
