package com.assignment.fulfilment.reconciliation.api;

import com.assignment.fulfilment.reconciliation.ReconciliationService;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRecordResponse;
import com.assignment.fulfilment.reconciliation.dto.ReconciliationRunResponse;
import com.assignment.fulfilment.reconciliation.dto.ResolveMismatchRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/run")
    public ResponseEntity<ReconciliationRunResponse> runReconciliation() {
        return ResponseEntity.ok(reconciliationService.runReconciliation());
    }

    @GetMapping("/records")
    public ResponseEntity<List<ReconciliationRecordResponse>> listRecords(
            @RequestParam(name = "status", required = false) ReconciliationStatus status,
            @RequestParam(name = "mismatchType", required = false) String mismatchType
    ) {
        return ResponseEntity.ok(reconciliationService.listRecords(status, mismatchType));
    }

    @GetMapping("/records/{recordId}")
    public ResponseEntity<ReconciliationRecordResponse> getRecord(@PathVariable("recordId") UUID recordId) {
        return ResponseEntity.ok(reconciliationService.getRecord(recordId));
    }

    @PostMapping("/records/{recordId}/resolve")
    public ResponseEntity<ReconciliationRecordResponse> resolveRecord(
            @PathVariable("recordId") UUID recordId,
            @RequestBody(required = false) ResolveMismatchRequest request
    ) {
        return ResponseEntity.ok(reconciliationService.resolveRecord(recordId, request));
    }
}
