package com.assignment.fulfilment.events.consumer;

import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.domain.ProcessedEvent;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IdempotentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventConsumer.class);

    public enum Outcome {
        /** Business effects applied and processed-event marker committed. */
        PROCESSED,
        /** Event was already processed by this consumer; no effects applied. */
        DUPLICATE,
        /** Attempt failed and was recorded; the event should be redelivered. */
        FAILED_WILL_RETRY,
        /** Attempt failed and retries are exhausted; the event was dead-lettered. */
        DEAD_LETTERED
    }

    private final ProcessedEventRepository processedEventRepository;
    private final EventRetryHandler eventRetryHandler;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotentEventConsumer(
            ProcessedEventRepository processedEventRepository,
            EventRetryHandler eventRetryHandler,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.processedEventRepository = processedEventRepository;
        this.eventRetryHandler = eventRetryHandler;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @FunctionalInterface
    public interface ConsumerAction {
        void execute(EventEnvelope envelope) throws Exception;
    }

    /**
     * Backwards-compatible variant returning {@code true} only when business effects were applied.
     */
    public boolean processIdempotently(
            String consumerName,
            EventEnvelope envelope,
            String topic,
            Integer partition,
            Long offset,
            ConsumerAction action
    ) {
        return process(consumerName, envelope, topic, partition, offset, action) == Outcome.PROCESSED;
    }

    /**
     * Executes the consumer action idempotently.
     * 1. Checks processed_events table.
     * 2. If already processed, skips execution (tolerates duplicate delivery) and audits the duplicate.
     * 3. If not processed, runs action and saves processed_events marker in ONE database transaction.
     *    The (consumer_name, event_id) primary key makes a concurrent duplicate fail instead of double-applying.
     * 4. If action throws, the transaction rolls back and the attempt is recorded via EventRetryHandler,
     *    which decides between retry and dead-letter.
     * 5. A success after earlier failed attempts marks the failure record as auto-recovered.
     */
    public Outcome process(
            String consumerName,
            EventEnvelope envelope,
            String topic,
            Integer partition,
            Long offset,
            ConsumerAction action
    ) {
        if (processedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, envelope.eventId())) {
            recordDuplicate(consumerName, envelope, topic, partition, offset);
            return Outcome.DUPLICATE;
        }

        try {
            Boolean applied = transactionTemplate.execute(status -> {
                // Double-check inside transaction
                if (processedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, envelope.eventId())) {
                    return false;
                }
                try {
                    action.execute(envelope);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ConsumerActionException(e);
                }
                processedEventRepository.saveAndFlush(new ProcessedEvent(consumerName, envelope.eventId(), Instant.now()));
                return true;
            });
            if (!Boolean.TRUE.equals(applied)) {
                recordDuplicate(consumerName, envelope, topic, partition, offset);
                return Outcome.DUPLICATE;
            }
            eventRetryHandler.markRecoveredIfFailing(envelope.eventId(), consumerName);
            return Outcome.PROCESSED;
        } catch (Exception e) {
            Throwable cause = e instanceof ConsumerActionException && e.getCause() != null ? e.getCause() : e;
            log.error("Error processing event {} ({}) on consumer {}: {}",
                    envelope.eventId(), envelope.eventType(), consumerName, cause.getMessage(), cause);
            EventFailure failure = eventRetryHandler.handleFailure(
                    envelope.eventId(),
                    topic,
                    partition,
                    offset,
                    consumerName,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                    serialize(envelope)
            );
            return failure.getStatus() == EventFailureStatus.DEAD_LETTERED ? Outcome.DEAD_LETTERED : Outcome.FAILED_WILL_RETRY;
        }
    }

    private void recordDuplicate(String consumerName, EventEnvelope envelope, String topic, Integer partition, Long offset) {
        log.info("Duplicate event detected: consumer={}, eventId={}, type={}. Skipping business effects.",
                consumerName, envelope.eventId(), envelope.eventType());
        try {
            auditLogRepository.save(new AuditLog(
                    "consumer_event",
                    envelope.aggregateId() != null ? envelope.aggregateId() : envelope.eventId(),
                    "DUPLICATE_EVENT_IGNORED",
                    consumerName,
                    "eventId=" + envelope.eventId() + ", type=" + envelope.eventType()
                            + ", topic=" + topic + ", partition=" + partition + ", offset=" + offset
            ));
        } catch (Exception auditError) {
            log.warn("Could not audit duplicate event {}: {}", envelope.eventId(), auditError.getMessage());
        }
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return envelope.payload();
        }
    }

    private static class ConsumerActionException extends RuntimeException {
        ConsumerActionException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
