package com.assignment.fulfilment.payments.domain;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    /** Simulated refund issued as a reconciliation corrective action. */
    REFUNDED
}
