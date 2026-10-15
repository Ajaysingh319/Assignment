package com.assignment.fulfilment.common.exception;

import com.assignment.fulfilment.orders.domain.OrderStatus;

public class InvalidOrderStateTransitionException extends RuntimeException {

    private final OrderStatus fromStatus;
    private final OrderStatus targetStatus;

    public InvalidOrderStateTransitionException(OrderStatus fromStatus, OrderStatus targetStatus, String message) {
        super(message);
        this.fromStatus = fromStatus;
        this.targetStatus = targetStatus;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getTargetStatus() {
        return targetStatus;
    }
}
