package com.assignment.fulfilment.events;

import com.assignment.fulfilment.events.consumer.EventRetryHandler;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.dto.EventFailureResponse;
import com.assignment.fulfilment.events.dto.EventFailureSummaryResponse;
import com.assignment.fulfilment.events.publisher.OutboxPublisher;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventFailureRepository eventFailureRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final EventRetryHandler eventRetryHandler;
    private final OrderEventConsumer orderEventConsumer;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public EventService(
            EventFailureRepository eventFailureRepository,
            ProcessedEventRepository processedEventRepository,
            EventRetryHandler eventRetryHandler,
            OrderEventConsumer orderEventConsumer,
            OutboxPublisher outboxPublisher,
            ObjectMapper objectMapper
    ) {
        this.eventFailureRepository = eventFailureRepository;
        this.processedEventRepository = processedEventRepository;
        this.eventRetryHandler = eventRetryHandler;
        this.orderEventConsumer = orderEventConsumer;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<EventFailureResponse> listFailures(EventFailureStatus status) {
        return eventRetryHandler.listFailures(status);
    }

    @Transactional(readOnly = true)
    public EventFailureResponse getFailure(UUID failureId) {
        if (failureId == null) {
            throw new IllegalArgumentException("failureId must not be null");
        }
        EventFailure failure = eventFailureRepository.findById(failureId)
                .orElseThrow(() -> new NoSuchElementException("Event failure not found: " + failureId));
        return EventFailureResponse.from(failure);
    }

    @Transactional(readOnly = true)
    public EventFailureSummaryResponse getSummary() {
        long total = eventFailureRepository.count();
        long deadLettered = eventFailureRepository.countByStatus(EventFailureStatus.DEAD_LETTERED);
        long retrying = eventFailureRepository.countByStatus(EventFailureStatus.RETRYING);
        long resolved = eventFailureRepository.countByStatus(EventFailureStatus.RESOLVED);
        long replayed = eventFailureRepository.countByStatus(EventFailureStatus.REPLAYED);
        return new EventFailureSummaryResponse(total, deadLettered, retrying, resolved, replayed);
    }

    /**
     * Safe, audited retry/replay mechanism.
     * Preserves original event ID, timestamps, failure history, and consumer idempotency.
     * Tolerates duplicate retries and duplicate message redelivery safely.
     */
    public EventFailureResponse retryFailure(UUID failureId) {
        if (failureId == null) {
            throw new IllegalArgumentException("failureId must not be null");
        }
        EventFailure failure = eventFailureRepository.findById(failureId)
                .orElseThrow(() -> new NoSuchElementException("Event failure not found: " + failureId));

        if (failure.getStatus() == EventFailureStatus.RESOLVED) {
            log.info("Event failure {} is already RESOLVED. Safe idempotent return.", failureId);
            return EventFailureResponse.from(failure);
        }

        eventRetryHandler.markReplayed(failureId);

        try {
            EventEnvelope envelope = objectMapper.readValue(failure.getPayload(), EventEnvelope.class);

            // Check if already processed by consumer (idempotency check)
            if (processedEventRepository.existsByIdConsumerNameAndIdEventId(failure.getConsumerName(), failure.getEventId())) {
                log.info("Event {} was already processed by consumer {}. Resolving failure record safely.",
                        failure.getEventId(), failure.getConsumerName());
                return eventRetryHandler.resolveFailure(failureId);
            }

            boolean success = orderEventConsumer.consumeEnvelope(
                    envelope,
                    failure.getTopic(),
                    failure.getPartitionNumber(),
                    failure.getOffsetNumber()
            );

            if (success) {
                return eventRetryHandler.resolveFailure(failureId);
            } else {
                if (processedEventRepository.existsByIdConsumerNameAndIdEventId(failure.getConsumerName(), failure.getEventId())) {
                    return eventRetryHandler.resolveFailure(failureId);
                }
                EventFailure updated = eventFailureRepository.findById(failureId).orElse(failure);
                return EventFailureResponse.from(updated);
            }
        } catch (Exception e) {
            log.error("Manual retry failed for event failure {}: {}", failureId, e.getMessage(), e);
            eventRetryHandler.recordManualRetryFailure(failureId, e.getMessage());
            EventFailure updated = eventFailureRepository.findById(failureId).orElse(failure);
            return EventFailureResponse.from(updated);
        }
    }

    /**
     * Retries all eligible failures (DEAD_LETTERED or RETRYING).
     */
    public List<EventFailureResponse> retryEligibleFailures() {
        List<EventFailure> eligible = eventFailureRepository.findByStatusInOrderByFirstFailedAtDesc(
                List.of(EventFailureStatus.DEAD_LETTERED, EventFailureStatus.RETRYING)
        );
        return eligible.stream()
                .map(f -> retryFailure(f.getId()))
                .toList();
    }

    public int triggerOutboxPublish() {
        return outboxPublisher.publishPendingEvents();
    }
}
