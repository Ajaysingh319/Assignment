package com.assignment.fulfilment.events.repository;

import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventFailureRepository extends JpaRepository<EventFailure, UUID> {

    List<EventFailure> findAllByOrderByFirstFailedAtDesc();

    List<EventFailure> findByStatusOrderByFirstFailedAtDesc(EventFailureStatus status);

    List<EventFailure> findByStatusInOrderByFirstFailedAtDesc(java.util.Collection<EventFailureStatus> statuses);

    Optional<EventFailure> findByEventIdAndConsumerName(String eventId, String consumerName);

    long countByStatus(EventFailureStatus status);

    List<EventFailure> findByEventIdIn(java.util.Collection<String> eventIds);
}
