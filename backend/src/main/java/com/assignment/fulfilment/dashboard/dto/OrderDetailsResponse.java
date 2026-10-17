package com.assignment.fulfilment.dashboard.dto;

import com.assignment.fulfilment.events.dto.OutboxEventResponse;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.OrderRequestResponse;
import com.assignment.fulfilment.payments.dto.PaymentCallbackResponse;
import com.assignment.fulfilment.payments.dto.PaymentResponse;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRecordResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the dashboard needs to explain one order, assembled from the persisted state of every module.
 */
public record OrderDetailsResponse(
        UUID id,
        OrderStatus status,
        String idempotencyKey,
        String currency,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant fulfilledAt,
        String reservationStatus,
        String paymentStatus,
        List<Line> lines,
        List<ReservationLine> reservations,
        List<StatusChange> statusHistory,
        List<PaymentResponse> payments,
        List<PaymentCallbackResponse> callbacks,
        List<OutboxEventResponse> events,
        List<ReconciliationRecordResponse> reconciliation,
        List<OrderRequestResponse> requests,
        List<AuditEntryResponse> auditTrail
) {
    public record Line(
            UUID productId,
            String sku,
            String productName,
            long quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
    }

    public record ReservationLine(
            UUID id,
            UUID productId,
            String sku,
            UUID warehouseId,
            String warehouseCode,
            String warehouseName,
            long quantity,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Instant releasedAt,
            Instant fulfilledAt
    ) {
    }

    public record StatusChange(
            Instant at,
            String status,
            String action,
            String actor,
            String details
    ) {
    }
}
