package com.assignment.fulfilment.events.repository;

import com.assignment.fulfilment.events.domain.ProcessedEvent;
import com.assignment.fulfilment.events.domain.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    boolean existsByIdConsumerNameAndIdEventId(String consumerName, String eventId);
}
