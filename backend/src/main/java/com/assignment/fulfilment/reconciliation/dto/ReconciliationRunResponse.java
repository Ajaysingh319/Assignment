package com.assignment.fulfilment.reconciliation.dto;

import java.util.List;

public record ReconciliationRunResponse(
        int detectedCount,
        int existingCount,
        List<ReconciliationRecordResponse> records
) {
}
