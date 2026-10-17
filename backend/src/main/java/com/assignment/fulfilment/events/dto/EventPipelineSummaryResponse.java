package com.assignment.fulfilment.events.dto;

public record EventPipelineSummaryResponse(
        long totalOutboxEvents,
        long pendingPublication,
        long publishFailing,
        long publishedViaKafka,
        long publishedSimulated,
        long consumerProcessed,
        long consumerRetrying,
        long deadLettered,
        long recoveredOrResolved,
        int faultInjectionRemaining,
        String faultInjectionEventType,
        boolean kafkaAvailable
) {
}
