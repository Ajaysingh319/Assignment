package com.assignment.fulfilment.payments.api;

import com.assignment.fulfilment.payments.PaymentService;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
import com.assignment.fulfilment.payments.dto.PaymentCallbackRequest;
import com.assignment.fulfilment.payments.dto.PaymentResponse;
import com.assignment.fulfilment.payments.dto.SimulatePaymentRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/orders/{orderId}/payments/simulate")
    public ResponseEntity<PaymentResponse> simulatePayment(
            @PathVariable UUID orderId,
            @Valid @RequestBody SimulatePaymentRequest request
    ) {
        PaymentResponse response = paymentService.simulatePayment(orderId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/payments/callbacks")
    public ResponseEntity<PaymentResponse> processCallback(
            @Valid @RequestBody PaymentCallbackRequest request
    ) {
        PaymentResponse response = paymentService.processCallback(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/payments")
    public ResponseEntity<List<PaymentResponse>> listPayments(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) PaymentStatus status
    ) {
        return ResponseEntity.ok(paymentService.listPayments(orderId, status));
    }

    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }

    @GetMapping("/payments/callbacks")
    public ResponseEntity<List<com.assignment.fulfilment.payments.dto.PaymentCallbackResponse>> listCallbacks(
            @RequestParam(required = false) UUID paymentId
    ) {
        return ResponseEntity.ok(paymentService.listCallbacks(paymentId));
    }

    @GetMapping("/payments/{paymentId}/callbacks")
    public ResponseEntity<List<com.assignment.fulfilment.payments.dto.PaymentCallbackResponse>> listPaymentCallbacks(
            @PathVariable UUID paymentId
    ) {
        return ResponseEntity.ok(paymentService.listCallbacks(paymentId));
    }
}
