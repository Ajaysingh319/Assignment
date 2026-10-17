package com.assignment.fulfilment.events.consumer;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.dto.EventFailureResponse;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(EventRetryHandler.class);

    private final EventFailureRepository eventFailureRepository;
    private final AuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int maxRetries;

    public EventRetryHandler(
            EventFailureRepository eventFailureRepository,
            AuditLogRepository auditLogRepository,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.events.max-retries:3}") int maxRetries
    ) {
        this.eventFailureRepository = eventFailureRepository;
        this.auditLogRepository = auditLogRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxRetries = maxRetries;
    }

    /**
     * Records failure, increments attempt count, routes to DLT when retries are exhausted.
     * Uses REQUIRES_NEW so failure logging is committed even if consumer transaction rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventFailure handleFailure(
            String eventId,
            String topic,
            Integer partition,
            Long offset,
            String consumerName,
            String error,
            String payload
    ) {
        Optional<EventFailure> existing = eventFailureRepository.findByEventIdAndConsumerName(eventId, consumerName);

        EventFailure failure;
        if (existing.isPresent()) {
            failure = existing.get();
            failure.recordRetry(error, maxRetries);
        } else {
            failure = new EventFailure(eventId, topic, partition, offset, consumerName, error, payload, maxRetries);
        }

        EventFailure saved = eventFailureRepository.save(failure);

        if (saved.getStatus() == EventFailureStatus.DEAD_LETTERED) {
            String dltTopic = topic + ".DLT";
            log.warn("Retries exhausted for event {} on consumer {} after {} attempts. Routing to DLT topic {}",
                    eventId, consumerName, saved.getAttemptCount(), dltTopic);
            forwardToDeadLetterTopic(dltTopic, eventId, payload);
            auditLogRepository.save(new AuditLog(
                    "event_failure",
                    saved.getId().toString(),
                    "EVENT_DEAD_LETTERED",
                    consumerName,
                    "eventId=" + eventId + ", attempts=" + saved.getAttemptCount() + ", dlt=" + dltTopic + ", lastError=" + error
            ));
        } else {
            log.warn("Event {} failed on consumer {} (attempt {}/{}); it will be redelivered",
                    eventId, consumerName, saved.getAttemptCount(), maxRetries);
        }

        return saved;
    }

    /**
     * A message that cannot be parsed can never succeed, so it is dead-lettered on the first attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventFailure recordPoisonMessage(String topic, Integer partition, Long offset, String consumerName, String rawMessage, String error) {
        String syntheticId = "unparseable-" + topic + "-" + partition + "-" + offset;
        EventFailure failure = eventFailureRepository.findByEventIdAndConsumerName(syntheticId, consumerName)
                .orElseGet(() -> new EventFailure(syntheticId, topic, partition, offset, consumerName, error, rawMessage, 1));
        EventFailure saved = eventFailureRepository.save(failure);
        log.error("Unparseable message at {}-{}@{} dead-lettered: {}", topic, partition, offset, error);
        forwardToDeadLetterTopic(topic + ".DLT", syntheticId, rawMessage);
        return saved;
    }

    /**
     * Called after a successful attempt: if earlier attempts of the same event failed, the failure record is
     * closed as RESOLVED so the dashboard shows "failed N times, then recovered".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRecoveredIfFailing(String eventId, String consumerName) {
        eventFailureRepository.findByEventIdAndConsumerName(eventId, consumerName)
                .filter(f -> f.getStatus() == EventFailureStatus.RETRYING || f.getStatus() == EventFailureStatus.REPLAYED)
                .ifPresent(f -> {
                    f.markResolved();
                    eventFailureRepository.save(f);
                    auditLogRepository.save(new AuditLog(
                            "event_failure",
                            f.getId().toString(),
                            "EVENT_RECOVERED_AFTER_RETRY",
                            consumerName,
                            "eventId=" + eventId + " succeeded after " + f.getAttemptCount() + " failed attempt(s)"
                    ));
                    log.info("Event {} recovered on consumer {} after {} failed attempt(s)", eventId, consumerName, f.getAttemptCount());
                });
    }

    private void forwardToDeadLetterTopic(String dltTopic, String key, String payload) {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate not available; dead-lettered event {} recorded in event_failures only", key);
            return;
        }
        try {
            kafkaTemplate.send(dltTopic, key, payload).get(5, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Dead-lettered event {} forwarded to {}", key, dltTopic);
        } catch (Exception e) {
            log.error("Failed to forward dead-lettered event {} to {}; it remains recorded in event_failures", key, dltTopic, e);
        }
    }

    @Transactional(readOnly = true)
    public List<EventFailureResponse> listFailures(EventFailureStatus status) {
        List<EventFailure> failures = status != null
                ? eventFailureRepository.findByStatusOrderByFirstFailedAtDesc(status)
                : eventFailureRepository.findAllByOrderByFirstFailedAtDesc();
        return failures.stream().map(EventFailureResponse::from).toList();
    }

    @Transactional
    public EventFailureResponse resolveFailure(UUID failureId) {
        EventFailure failure = eventFailureRepository.findById(failureId)
                .orElseThrow(() -> new NoSuchElementException("Event failure not found: " + failureId));

        failure.markResolved();
        eventFailureRepository.save(failure);

        auditLogRepository.save(new AuditLog(
                "event_failure",
                failureId.toString(),
                "EVENT_FAILURE_RESOLVED",
                "OPERATOR",
                "Resolved manually for eventId=" + failure.getEventId()
        ));

        return EventFailureResponse.from(failure);
    }

    @Transactional
    public EventFailure markReplayed(UUID failureId) {
        EventFailure failure = eventFailureRepository.findById(failureId)
                .orElseThrow(() -> new NoSuchElementException("Event failure not found: " + failureId));

        failure.markReplayed();
        EventFailure saved = eventFailureRepository.save(failure);

        auditLogRepository.save(new AuditLog(
                "event_failure",
                failureId.toString(),
                "EVENT_RETRY_INITIATED",
                "OPERATOR",
                "Replay requested for eventId=" + failure.getEventId()
        ));

        return saved;
    }

    @Transactional
    public EventFailure recordManualRetryFailure(UUID failureId, String error) {
        EventFailure failure = eventFailureRepository.findById(failureId)
                .orElseThrow(() -> new NoSuchElementException("Event failure not found: " + failureId));

        failure.recordRetry("Manual retry error: " + error, maxRetries);
        EventFailure saved = eventFailureRepository.save(failure);

        auditLogRepository.save(new AuditLog(
                "event_failure",
                failureId.toString(),
                "EVENT_RETRY_FAILED",
                "OPERATOR",
                "Manual retry failed for eventId=" + failure.getEventId() + ": " + error
        ));

        return saved;
    }
}
