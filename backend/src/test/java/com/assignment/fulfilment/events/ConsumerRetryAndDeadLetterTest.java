package com.assignment.fulfilment.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.events.consumer.ConsumerFaultInjector;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.dto.EventEnvelope;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
import com.assignment.fulfilment.events.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Drives the real listener entry point ({@link OrderEventConsumer#onMessage}) the way the Kafka container does:
 * a thrown exception means "redeliver", a normal return means "commit offset".
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConsumerRetryAndDeadLetterTest {

    @Autowired
    private OrderEventConsumer consumer;

    @Autowired
    private ConsumerFaultInjector faultInjector;

    @Autowired
    private EventFailureRepository eventFailureRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void disarm() {
        faultInjector.disarm();
    }

    private String message(String eventId, String aggregateId) throws Exception {
        return objectMapper.writeValueAsString(new EventEnvelope(
                eventId, "ORDER_CREATED", 1, Instant.now(), "ORDER", aggregateId, null,
                "{\"orderId\":\"" + aggregateId + "\"}"));
    }

    @Test
    @DisplayName("Transient failure: redelivered twice, then processed once; failure record closed as RESOLVED")
    void transientFailureRecovers() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        String msg = message(eventId, orderId);
        faultInjector.arm(2, null, orderId);

        assertThatThrownBy(() -> consumer.onMessage(msg, "order-events", 0, 1L))
                .isInstanceOf(OrderEventConsumer.RetryableEventException.class);
        assertThatThrownBy(() -> consumer.onMessage(msg, "order-events", 0, 1L))
                .isInstanceOf(OrderEventConsumer.RetryableEventException.class);

        EventFailure retrying = eventFailureRepository.findByEventIdAndConsumerName(eventId, consumer.getConsumerName()).orElseThrow();
        assertThat(retrying.getStatus()).isEqualTo(EventFailureStatus.RETRYING);
        assertThat(retrying.getAttemptCount()).isEqualTo(2);
        assertThat(processedEventRepository.existsByIdConsumerNameAndIdEventId(consumer.getConsumerName(), eventId)).isFalse();

        consumer.onMessage(msg, "order-events", 0, 1L); // third delivery succeeds

        assertThat(processedEventRepository.existsByIdConsumerNameAndIdEventId(consumer.getConsumerName(), eventId)).isTrue();
        EventFailure recovered = eventFailureRepository.findById(retrying.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(EventFailureStatus.RESOLVED);
        assertThat(auditLogRepository.findByEntityTypeAndEntityId("consumer_event", orderId))
                .filteredOn(a -> a.getAction().equals("CONSUMED_ORDER_CREATED")).hasSize(1);
    }

    @Test
    @DisplayName("Duplicate delivery after success: no exception, no second business effect, duplicate audited")
    void duplicateDeliveryIsAbsorbed() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        String msg = message(eventId, orderId);

        consumer.onMessage(msg, "order-events", 0, 5L);
        consumer.onMessage(msg, "order-events", 0, 6L);
        consumer.onMessage(msg, "order-events", 0, 7L);

        assertThat(auditLogRepository.findByEntityTypeAndEntityId("consumer_event", orderId))
                .filteredOn(a -> a.getAction().equals("CONSUMED_ORDER_CREATED")).hasSize(1);
        assertThat(auditLogRepository.findByEntityTypeAndEntityId("consumer_event", orderId))
                .filteredOn(a -> a.getAction().equals("DUPLICATE_EVENT_IGNORED")).hasSize(2);
    }

    @Test
    @DisplayName("Persistent failure: dead-lettered after max retries (3); the offset is then committed")
    void persistentFailureIsDeadLettered() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        String msg = message(eventId, orderId);
        faultInjector.arm(10, "ORDER_CREATED", orderId);

        assertThatThrownBy(() -> consumer.onMessage(msg, "order-events", 0, 9L))
                .isInstanceOf(OrderEventConsumer.RetryableEventException.class);
        assertThatThrownBy(() -> consumer.onMessage(msg, "order-events", 0, 9L))
                .isInstanceOf(OrderEventConsumer.RetryableEventException.class);
        consumer.onMessage(msg, "order-events", 0, 9L); // third attempt: dead-lettered, returns normally

        EventFailure failure = eventFailureRepository.findByEventIdAndConsumerName(eventId, consumer.getConsumerName()).orElseThrow();
        assertThat(failure.getStatus()).isEqualTo(EventFailureStatus.DEAD_LETTERED);
        assertThat(failure.getAttemptCount()).isEqualTo(3);
        assertThat(failure.getLastError()).contains("Simulated worker failure");
        // The full envelope is stored so a manual retry can re-run the original event
        assertThat(objectMapper.readValue(failure.getPayload(), EventEnvelope.class).eventId()).isEqualTo(eventId);
        assertThat(processedEventRepository.existsByIdConsumerNameAndIdEventId(consumer.getConsumerName(), eventId)).isFalse();
    }

    @Test
    @DisplayName("Unparseable message is dead-lettered immediately instead of being silently dropped")
    void poisonMessageIsDeadLettered() {
        consumer.onMessage("this is not json", "order-events", 0, 777L);

        EventFailure failure = eventFailureRepository
                .findByEventIdAndConsumerName("unparseable-order-events-0-777", consumer.getConsumerName())
                .orElseThrow();
        assertThat(failure.getStatus()).isEqualTo(EventFailureStatus.DEAD_LETTERED);
        assertThat(failure.getPayload()).isEqualTo("this is not json");
    }
}
