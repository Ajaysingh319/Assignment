package com.assignment.fulfilment.orders.domain;

public enum OrderRequestOutcome {
    /** A new order was created and its inventory reserved. */
    ACCEPTED,
    /** Same Idempotency-Key and payload as an earlier request: the existing order was returned, nothing new created. */
    IDEMPOTENT_REPLAY,
    /** Not enough stock for at least one line; nothing was reserved and no order was created. */
    REJECTED_INSUFFICIENT_STOCK,
    /** Idempotency-Key reused with a different payload. */
    REJECTED_IDEMPOTENCY_CONFLICT,
    /** Unknown or inactive product, or an otherwise invalid request. */
    REJECTED_INVALID
}
