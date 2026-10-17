package com.assignment.fulfilment.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.assignment.fulfilment.events.domain.OutboxEvent;
import com.assignment.fulfilment.events.publisher.OutboxPublisher;
import com.assignment.fulfilment.events.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherUnitTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static OutboxEvent orderEvent() {
        return new OutboxEvent(UUID.randomUUID(), "ORDER", UUID.randomUUID().toString(), "ORDER_CREATED", 1,
                "{\"status\":\"PENDING_PAYMENT\"}", null);
    }

    @Test
    @DisplayName("Without Kafka and without explicit simulation, events are NOT marked published")
    void noKafkaLeavesEventsPending() {
        OutboxPublisher publisher = new OutboxPublisher(repository, objectMapper, null, false, 1000);

        assertThat(publisher.publishPendingEvents()).isZero();
        verify(repository, never()).findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

        OutboxEvent event = orderEvent();
        assertThat(publisher.publishSingleEvent(event)).isFalse();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("KafkaTemplate is not available");
    }

    @Test
    @DisplayName("A failed Kafka send records the attempt and error and keeps the event pending")
    @SuppressWarnings("unchecked")
    void failedSendKeepsEventPending() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));
        OutboxPublisher publisher = new OutboxPublisher(repository, objectMapper, kafka, false, 1000);
        OutboxEvent event = orderEvent();

        assertThat(publisher.publishSingleEvent(event)).isFalse();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPublishedVia()).isNull();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker unavailable");
        verify(repository).save(event);
    }

    @Test
    @DisplayName("An acknowledged Kafka send marks the event published via KAFKA on the routed topic")
    @SuppressWarnings("unchecked")
    void acknowledgedSendMarksPublished() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEvent event = orderEvent();
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("order-events", 0), 42L, 0, 0L, 0, 0);
        SendResult<String, String> result = new SendResult<>(new ProducerRecord<>("order-events", "k", "v"), metadata);
        when(kafka.send(eq("order-events"), eq(event.getAggregateId()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        OutboxPublisher publisher = new OutboxPublisher(repository, objectMapper, kafka, false, 1000);

        assertThat(publisher.publishPendingEvents()).isEqualTo(1);

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getPublishedVia()).isEqualTo(OutboxEvent.VIA_KAFKA);
        assertThat(event.getLastError()).isNull();
        verify(kafka).send(eq("order-events"), eq(event.getAggregateId()), any());
    }

    @Test
    @DisplayName("Explicit test-only simulation stamps published_via=SIMULATED, never KAFKA")
    void simulationIsDistinguishable() {
        OutboxPublisher publisher = new OutboxPublisher(repository, objectMapper, null, true, 1000);
        OutboxEvent event = orderEvent();

        assertThat(publisher.publishSingleEvent(event)).isTrue();
        assertThat(event.getPublishedVia()).isEqualTo(OutboxEvent.VIA_SIMULATED);
    }

    @Test
    @DisplayName("Aggregate types are routed to their topics")
    void topicRouting() {
        assertThat(OutboxPublisher.determineTopic("ORDER")).isEqualTo("order-events");
        assertThat(OutboxPublisher.determineTopic("PAYMENT")).isEqualTo("payment-events");
        assertThat(OutboxPublisher.determineTopic("INVENTORY")).isEqualTo("inventory-events");
    }
}
