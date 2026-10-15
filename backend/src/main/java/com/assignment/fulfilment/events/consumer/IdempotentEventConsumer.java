package com.assignment.fulfilment.events.consumer;

import com.assignment.fulfilment.events.domain.ProcessedEvent;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdempotentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventConsumer.class);

    private final ProcessedEventRepository processedEventRepository;
    private final EventRetryHandler eventRetryHandler;

    public IdempotentEventConsumer(
            ProcessedEventRepository processedEventRepository,
            EventRetryHandler eventRetryHandler
    ) {
        this.processedEventRepository = processedEventRepository;
        this.eventRetryHandler = eventRetryHandler;
    }

    @FunctionalInterface
    public interface ConsumerAction {
        void execute(EventEnvelope envelope) throws Exception;
    }

    /**
     * Executes the consumer action idempotently.
     * 1. Checks processed_events table.
     * 2. If already processed, skips execution immediately (tolerates duplicate delivery).
     * 3. If not processed, runs action and saves processed_events marker in the SAME transaction.
     * 4. If action throws an exception, rolls back transaction and records failure via EventRetryHandler.
     */
    public boolean processIdempotently(
            String consumerName,
            EventEnvelope envelope,
            String topic,
            Integer partition,
            Long offset,
            ConsumerAction action
    ) {
        if (processedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, envelope.eventId())) {
            log.info("Duplicate event detected: consumer={}, eventId={}. Skipping business effects.", consumerName, envelope.eventId());
            return false;
        }

        try {
            executeWithinTransaction(consumerName, envelope, action);
            return true;
        } catch (Exception e) {
            log.error("Error processing event {} on consumer {}: {}", envelope.eventId(), consumerName, e.getMessage(), e);
            eventRetryHandler.handleFailure(
                    envelope.eventId(),
                    topic,
                    partition,
                    offset,
                    consumerName,
                    e.getMessage(),
                    envelope.payload()
            );
            return false;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void executeWithinTransaction(
            String consumerName,
            EventEnvelope envelope,
            ConsumerAction action
    ) throws Exception {
        // Double-check inside transaction
        if (processedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, envelope.eventId())) {
            log.info("Duplicate event detected inside transaction: consumer={}, eventId={}. Skipping.", consumerName, envelope.eventId());
            return;
        }

        // Execute consumer database effects
        action.execute(envelope);

        // Commit processed-event marker atomically in the same database transaction
        processedEventRepository.save(new ProcessedEvent(consumerName, envelope.eventId(), Instant.now()));
    }
}
