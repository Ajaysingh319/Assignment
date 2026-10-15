package com.assignment.fulfilment.events.publisher;

import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.repository.OutboxEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Persists the outbox event in the SAME transaction as the associated business-state change.
     * Uses Propagation.MANDATORY or REQUIRED to participate in the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OutboxEvent publish(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            String payload,
            String correlationId
    ) {
        UUID eventId = UUID.randomUUID();
        OutboxEvent outboxEvent = new OutboxEvent(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                eventVersion,
                payload,
                correlationId
        );
        return outboxEventRepository.save(outboxEvent);
    }
}
