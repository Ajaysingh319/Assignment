package com.assignment.fulfilment.events.dto;

import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.domain.ProcessedEvent;
import com.assignment.fulfilment.events.publisher.OutboxPublisher;
import java.time.Instant;
import java.util.UUID;

/**
 * One outbox event with its full processing trail: publication to Kafka and consumption by the worker.
 */
public record OutboxEventResponse(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String topic,
        String payload,
        Instant createdAt,
        String publishStatus,
        Instant publishedAt,
        String publishedVia,
        int publishAttempts,
        String lastPublishError,
        String consumerStatus,
        Instant consumedAt,
        Integer consumerAttempts,
        String consumerLastError,
        UUID consumerFailureId
) {
    public static OutboxEventResponse from(OutboxEvent event, ProcessedEvent processed, EventFailure failure) {
        String publishStatus = event.getPublishedAt() != null
                ? "PUBLISHED"
                : event.getAttemptCount() > 0 ? "PUBLISH_FAILING" : "PENDING";

        String consumerStatus;
        if (processed != null) {
            consumerStatus = failure != null ? "PROCESSED_AFTER_RETRY" : "PROCESSED";
        } else if (failure != null) {
            consumerStatus = failure.getStatus().name();
        } else if (event.getPublishedAt() != null && "KAFKA".equals(event.getPublishedVia())) {
            consumerStatus = "AWAITING_CONSUMER";
        } else {
            consumerStatus = "NOT_DELIVERED";
        }

        return new OutboxEventResponse(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                OutboxPublisher.determineTopic(event.getAggregateType()),
                event.getPayload(),
                event.getCreatedAt(),
                publishStatus,
                event.getPublishedAt(),
                event.getPublishedVia(),
                event.getAttemptCount(),
                event.getLastError(),
                consumerStatus,
                processed != null ? processed.getProcessedAt() : null,
                failure != null ? failure.getAttemptCount() : null,
                failure != null ? failure.getLastError() : null,
                failure != null ? failure.getId() : null
        );
    }
}
