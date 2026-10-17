package com.assignment.fulfilment.events.consumer;

import com.assignment.fulfilment.events.dto.EventEnvelope;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Explicit, operator-armed fault injection used to demonstrate worker failures, Kafka redelivery,
 * bounded retries and dead-lettering against real events. Disarmed by default; arming it is audited.
 * Each consumption attempt that matches the optional event-type / aggregate filters consumes one injected failure.
 */
@Component
public class ConsumerFaultInjector {

    private static final Logger log = LoggerFactory.getLogger(ConsumerFaultInjector.class);

    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private volatile String eventTypeFilter;
    private volatile String aggregateIdFilter;

    public synchronized void arm(int failures, String eventType) {
        arm(failures, eventType, null);
    }

    public synchronized void arm(int failures, String eventType, String aggregateId) {
        if (failures < 0) {
            throw new IllegalArgumentException("failures must be >= 0");
        }
        eventTypeFilter = eventType == null || eventType.isBlank() ? null : eventType.trim().toUpperCase();
        aggregateIdFilter = aggregateId == null || aggregateId.isBlank() ? null : aggregateId.trim();
        remainingFailures.set(failures);
        log.warn("Consumer fault injection armed: next {} matching attempts will fail (eventType={}, aggregateId={})",
                failures, eventTypeFilter, aggregateIdFilter);
    }

    public synchronized void disarm() {
        remainingFailures.set(0);
        eventTypeFilter = null;
        aggregateIdFilter = null;
    }

    public int getRemainingFailures() {
        return remainingFailures.get();
    }

    public String getEventTypeFilter() {
        return eventTypeFilter;
    }

    public String getAggregateIdFilter() {
        return aggregateIdFilter;
    }

    void maybeFail(EventEnvelope envelope) {
        String typeFilter = eventTypeFilter;
        if (typeFilter != null && !typeFilter.equalsIgnoreCase(envelope.eventType())) {
            return;
        }
        String aggregateFilter = aggregateIdFilter;
        if (aggregateFilter != null && !aggregateFilter.equals(envelope.aggregateId())) {
            return;
        }
        int before = remainingFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0);
        if (before > 0) {
            throw new SimulatedConsumerFailureException(
                    "Simulated worker failure (fault injection) while processing " + envelope.eventType()
                            + " eventId=" + envelope.eventId() + "; " + (before - 1) + " injected failures remaining");
        }
    }

    public static class SimulatedConsumerFailureException extends RuntimeException {
        public SimulatedConsumerFailureException(String message) {
            super(message);
        }
    }
}
