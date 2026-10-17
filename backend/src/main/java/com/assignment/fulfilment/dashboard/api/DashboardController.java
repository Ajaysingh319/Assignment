package com.assignment.fulfilment.dashboard.api;

import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.dashboard.OrderDetailsService;
import com.assignment.fulfilment.dashboard.dto.AuditEntryResponse;
import com.assignment.fulfilment.dashboard.dto.OrderDetailsResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final OrderDetailsService orderDetailsService;
    private final AuditLogRepository auditLogRepository;

    public DashboardController(OrderDetailsService orderDetailsService, AuditLogRepository auditLogRepository) {
        this.orderDetailsService = orderDetailsService;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/api/orders/{orderId}/details")
    public ResponseEntity<OrderDetailsResponse> orderDetails(@PathVariable("orderId") UUID orderId) {
        return ResponseEntity.ok(orderDetailsService.getDetails(orderId));
    }

    /**
     * Audit trail, newest first, optionally filtered by entity type (e.g. reconciliation, reconciliation_run,
     * payment, inventory, event_failure) and action.
     */
    @GetMapping("/api/audit")
    @Transactional(readOnly = true)
    public ResponseEntity<List<AuditEntryResponse>> audit(
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        int size = Math.max(1, Math.min(limit, 500));
        return ResponseEntity.ok(auditLogRepository.search(blankToNull(entityType), blankToNull(action), PageRequest.of(0, size))
                .stream().map(AuditEntryResponse::from).toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
