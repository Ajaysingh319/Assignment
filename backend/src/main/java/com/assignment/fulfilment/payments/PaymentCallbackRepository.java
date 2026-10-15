package com.assignment.fulfilment.payments;

import com.assignment.fulfilment.payments.domain.PaymentCallback;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCallbackRepository extends JpaRepository<PaymentCallback, UUID> {

    boolean existsByProviderCallbackId(String providerCallbackId);

    Optional<PaymentCallback> findByProviderCallbackId(String providerCallbackId);

    java.util.List<PaymentCallback> findAllByOrderByReceivedAtDesc();

    java.util.List<PaymentCallback> findByPaymentIdOrderByReceivedAtDesc(UUID paymentId);
}
