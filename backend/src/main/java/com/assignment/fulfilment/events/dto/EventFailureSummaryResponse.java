package com.assignment.fulfilment.events.dto;

public record EventFailureSummaryResponse(
        long totalFailures,
        long deadLetteredCount,
        long retryingCount,
        long resolvedCount,
        long replayedCount
) {
}
