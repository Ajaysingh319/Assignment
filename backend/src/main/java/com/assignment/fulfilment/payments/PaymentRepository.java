package com.assignment.fulfilment.payments;

import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByProviderReference(String providerReference);

    List<Payment> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    List<Payment> findAllByOrderByCreatedAtDesc();

    List<Payment> findByStatusOrderByCreatedAtDesc(PaymentStatus status);
}
