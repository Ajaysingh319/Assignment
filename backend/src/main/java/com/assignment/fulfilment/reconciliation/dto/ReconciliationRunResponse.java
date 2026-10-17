package com.assignment.fulfilment.reconciliation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationRunResponse(
        int detectedCount,
        int existingCount,
        List<ReconciliationRecordResponse> records,
        UUID runId,
        Instant runAt,
        int ordersChecked,
        int paymentsChecked
) {
    public ReconciliationRunResponse(int detectedCount, int existingCount, List<ReconciliationRecordResponse> records) {
        this(detectedCount, existingCount, records, null, null, 0, 0);
    }
}
