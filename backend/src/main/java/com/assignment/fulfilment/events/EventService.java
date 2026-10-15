package com.assignment.fulfilment.events;

import com.assignment.fulfilment.events.consumer.EventRetryHandler;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.dto.EventFailureResponse;
import com.assignment.fulfilment.events.publisher.OutboxPublisher;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final EventFailureRepository eventFailureRepository;
    private final EventRetryHandler eventRetryHandler;
    private final OrderEventConsumer orderEventConsumer;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public EventService(
            EventFailureRepository eventFailureRepository,
            EventRetryHandler eventRetryHandler,
            OrderEventConsumer orderEventConsumer,
            OutboxPublisher outboxPublisher,
            ObjectMapper objectMapper
    ) {
        this.eventFailureRepository = eventFailureRepository;
        this.eventRetryHandler = eventRetryHandler;
        this.orderEventConsumer = orderEventConsumer;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    public List<EventFailureResponse> listFailures(EventFailureStatus status) {
        return eventRetryHandler.listFailures(status);
    }

    /**
     * Safe, audited retry/replay mechanism.
     * Preserves original event ID and consumer idempotency.
     */
    public EventFailureResponse retryFailure(UUID failureId) {
        EventFailure failure = eventFailureRepository.findById(failureId)
                .orElseThrow(() -> new NoSuchElementException("Event failure not found: " + failureId));

        eventRetryHandler.markReplayed(failureId);

        try {
            EventEnvelope envelope = objectMapper.readValue(failure.getPayload(), EventEnvelope.class);

            boolean success = orderEventConsumer.consumeEnvelope(
                    envelope,
                    failure.getTopic(),
                    failure.getPartitionNumber(),
                    failure.getOffsetNumber()
            );

            if (success) {
                return eventRetryHandler.resolveFailure(failureId);
            } else {
                return EventFailureResponse.from(failure);
            }
        } catch (Exception e) {
            failure.recordRetry("Replay failed: " + e.getMessage(), 3);
            eventFailureRepository.save(failure);
            return EventFailureResponse.from(failure);
        }
    }

    public int triggerOutboxPublish() {
        return outboxPublisher.publishPendingEvents();
    }
}
