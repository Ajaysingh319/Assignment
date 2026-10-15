package com.assignment.fulfilment.events.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_failures")
public class EventFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "partition_number")
    private Integer partitionNumber;

    @Column(name = "offset_number")
    private Long offsetNumber;

    @Column(name = "consumer_name", nullable = false, length = 128)
    private String consumerName;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EventFailureStatus status;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @Column(name = "last_failed_at", nullable = false)
    private Instant lastFailedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected EventFailure() {
    }

    public EventFailure(
            String eventId,
            String topic,
            Integer partitionNumber,
            Long offsetNumber,
            String consumerName,
            String lastError,
            String payload,
            int maxRetries
    ) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        this.eventId = eventId;
        this.topic = topic != null ? topic : "unknown";
        this.partitionNumber = partitionNumber;
        this.offsetNumber = offsetNumber;
        this.consumerName = consumerName;
        this.lastError = lastError;
        this.payload = payload;
        this.attemptCount = 1;
        this.status = 1 >= maxRetries ? EventFailureStatus.DEAD_LETTERED : EventFailureStatus.RETRYING;
        this.firstFailedAt = Instant.now();
        this.lastFailedAt = this.firstFailedAt;
    }

    public void recordRetry(String error, int maxRetries) {
        this.attemptCount++;
        this.lastError = error;
        this.lastFailedAt = Instant.now();
        if (this.attemptCount >= maxRetries) {
            this.status = EventFailureStatus.DEAD_LETTERED;
        } else {
            this.status = EventFailureStatus.RETRYING;
        }
    }

    public void markReplayed() {
        this.status = EventFailureStatus.REPLAYED;
        this.lastFailedAt = Instant.now();
    }

    public void markResolved() {
        this.status = EventFailureStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public Integer getPartitionNumber() {
        return partitionNumber;
    }

    public Long getOffsetNumber() {
        return offsetNumber;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public EventFailureStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }

    public Instant getLastFailedAt() {
        return lastFailedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
