package com.assignment.fulfilment.reconciliation;

import com.assignment.fulfilment.reconciliation.domain.ReconciliationRecord;
import com.assignment.fulfilment.reconciliation.domain.ReconciliationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRecordRepository extends JpaRepository<ReconciliationRecord, UUID> {

    Optional<ReconciliationRecord> findByMismatchKey(String mismatchKey);

    List<ReconciliationRecord> findAllByOrderByDetectedAtDesc();

    List<ReconciliationRecord> findByStatusOrderByDetectedAtDesc(ReconciliationStatus status);

    Optional<ReconciliationRecord> findFirstByMismatchTypeAndPaymentId(String mismatchType, UUID paymentId);

    List<ReconciliationRecord> findByOrderIdOrderByDetectedAtAsc(UUID orderId);
}
