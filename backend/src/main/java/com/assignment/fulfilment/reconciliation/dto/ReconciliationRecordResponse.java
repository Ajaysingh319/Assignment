package com.assignment.fulfilment.reconciliation.dto;

import com.assignment.fulfilment.reconciliation.domain.ReconciliationRecord;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationRecordResponse(
        UUID id,
        String mismatchKey,
        UUID orderId,
        UUID paymentId,
        String mismatchType,
        ReconciliationStatus status,
        String details,
        Instant detectedAt,
        Instant updatedAt,
        Instant resolvedAt
) {
    public static ReconciliationRecordResponse from(ReconciliationRecord record) {
        return new ReconciliationRecordResponse(
                record.getId(),
                record.getMismatchKey(),
                record.getOrderId(),
                record.getPaymentId(),
                record.getMismatchType(),
                record.getStatus(),
                record.getDetails(),
                record.getDetectedAt(),
                record.getUpdatedAt(),
                record.getResolvedAt()
        );
    }
}
