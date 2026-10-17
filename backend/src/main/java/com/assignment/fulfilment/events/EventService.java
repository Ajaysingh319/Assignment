package com.assignment.fulfilment.events;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.consumer.ConsumerFaultInjector;
import com.assignment.fulfilment.events.consumer.EventRetryHandler;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.domain.ProcessedEvent;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.dto.EventFailureResponse;
import com.assignment.fulfilment.events.dto.EventFailureSummaryResponse;
import com.assignment.fulfilment.events.dto.EventPipelineSummaryResponse;
import com.assignment.fulfilment.events.dto.FaultInjectionRequest;
import com.assignment.fulfilment.events.dto.OutboxEventResponse;
import com.assignment.fulfilment.events.publisher.OutboxPublisher;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
import com.assignment.fulfilment.events.repository.OutboxEventRepository;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final OutboxEventRepository outboxEventRepository;
    private final ConsumerFaultInjector faultInjector;
    private final AuditLogRepository auditLogRepository;

    public EventService(
            EventFailureRepository eventFailureRepository,
            ProcessedEventRepository processedEventRepository,
            EventRetryHandler eventRetryHandler,
            OrderEventConsumer orderEventConsumer,
            OutboxPublisher outboxPublisher,
            ObjectMapper objectMapper,
            OutboxEventRepository outboxEventRepository,
            ConsumerFaultInjector faultInjector,
            AuditLogRepository auditLogRepository
    ) {
        this.eventFailureRepository = eventFailureRepository;
        this.processedEventRepository = processedEventRepository;
        this.eventRetryHandler = eventRetryHandler;
        this.orderEventConsumer = orderEventConsumer;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
        this.outboxEventRepository = outboxEventRepository;
        this.faultInjector = faultInjector;
        this.auditLogRepository = auditLogRepository;
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

    /**
     * Lists outbox events joined with their consumer outcome.
     *
     * @param status null/ALL, PENDING (not yet published) or PUBLISHED
     */
    @Transactional(readOnly = true)
    public List<OutboxEventResponse> listOutboxEvents(String status) {
        List<OutboxEvent> events;
        if ("PENDING".equalsIgnoreCase(status)) {
            events = outboxEventRepository.findTop300ByPublishedAtIsNullOrderByCreatedAtDesc();
        } else if ("PUBLISHED".equalsIgnoreCase(status)) {
            events = outboxEventRepository.findTop300ByPublishedAtIsNotNullOrderByCreatedAtDesc();
        } else {
            events = outboxEventRepository.findTop300ByOrderByCreatedAtDesc();
        }
        return withConsumerOutcome(events);
    }

    /**
     * Event trail for a set of aggregates (an order and its payments), oldest first.
     */
    @Transactional(readOnly = true)
    public List<OutboxEventResponse> eventsForAggregates(Collection<String> aggregateIds) {
        if (aggregateIds.isEmpty()) {
            return List.of();
        }
        return withConsumerOutcome(outboxEventRepository.findByAggregateIdInOrderByCreatedAtAsc(aggregateIds));
    }

    private List<OutboxEventResponse> withConsumerOutcome(List<OutboxEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<String> eventIds = events.stream().map(e -> e.getId().toString()).toList();
        Map<String, ProcessedEvent> processed = processedEventRepository.findByIdEventIdIn(eventIds).stream()
                .filter(p -> orderEventConsumer.getConsumerName().equals(p.getId().getConsumerName()))
                .collect(Collectors.toMap(p -> p.getId().getEventId(), Function.identity(), (a, b) -> a));
        Map<String, EventFailure> failures = eventFailureRepository.findByEventIdIn(eventIds).stream()
                .filter(f -> orderEventConsumer.getConsumerName().equals(f.getConsumerName()))
                .collect(Collectors.toMap(EventFailure::getEventId, Function.identity(), (a, b) -> a));
        return events.stream()
                .map(e -> OutboxEventResponse.from(e, processed.get(e.getId().toString()), failures.get(e.getId().toString())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventPipelineSummaryResponse getPipelineSummary() {
        long total = outboxEventRepository.count();
        long pending = outboxEventRepository.countByPublishedAtIsNull();
        long failing = outboxEventRepository.countByPublishedAtIsNullAndAttemptCountGreaterThan(0);
        long viaKafka = outboxEventRepository.countByPublishedVia(OutboxEvent.VIA_KAFKA);
        long simulated = outboxEventRepository.countByPublishedVia(OutboxEvent.VIA_SIMULATED);
        long processed = processedEventRepository.count();
        long retrying = eventFailureRepository.countByStatus(EventFailureStatus.RETRYING);
        long dead = eventFailureRepository.countByStatus(EventFailureStatus.DEAD_LETTERED);
        long resolved = eventFailureRepository.countByStatus(EventFailureStatus.RESOLVED);
        return new EventPipelineSummaryResponse(
                total, pending, failing, viaKafka, simulated, processed, retrying, dead, resolved,
                faultInjector.getRemainingFailures(), faultInjector.getEventTypeFilter(),
                outboxPublisher.isKafkaAvailable()
        );
    }

    /**
     * Sends an already-published event to Kafka again. The consumer must detect it as a duplicate
     * (processed_events) and skip business effects; the duplicate is visible in the audit log.
     */
    public OutboxEventResponse redeliverEvent(UUID outboxEventId) {
        OutboxEvent event = outboxEventRepository.findById(outboxEventId)
                .orElseThrow(() -> new NoSuchElementException("Outbox event not found: " + outboxEventId));
        if (event.getPublishedAt() == null) {
            throw new IllegalStateException("Outbox event " + outboxEventId + " has not been published yet; nothing to re-deliver");
        }
        try {
            outboxPublisher.republish(event);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Re-delivery to Kafka failed: " + e.getMessage(), e);
        }
        auditLogRepository.save(new AuditLog(
                "outbox_event",
                event.getAggregateId(),
                "EVENT_REDELIVERED",
                "OPERATOR",
                "Duplicate delivery requested for eventId=" + event.getId() + ", type=" + event.getEventType()
        ));
        return withConsumerOutcome(List.of(event)).get(0);
    }

    public Map<String, Object> armFaultInjection(FaultInjectionRequest request) {
        faultInjector.arm(request.failures(), request.eventType(), request.aggregateId());
        auditLogRepository.save(new AuditLog(
                "fault_injection",
                orderEventConsumer.getConsumerName(),
                request.failures() > 0 ? "FAULT_INJECTION_ARMED" : "FAULT_INJECTION_DISARMED",
                "OPERATOR",
                "failures=" + request.failures() + ", eventType=" + faultInjector.getEventTypeFilter()
                        + ", aggregateId=" + faultInjector.getAggregateIdFilter()
        ));
        return faultInjectionState();
    }

    public Map<String, Object> faultInjectionState() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("remainingFailures", faultInjector.getRemainingFailures());
        state.put("eventType", faultInjector.getEventTypeFilter());
        state.put("aggregateId", faultInjector.getAggregateIdFilter());
        return state;
    }
}
