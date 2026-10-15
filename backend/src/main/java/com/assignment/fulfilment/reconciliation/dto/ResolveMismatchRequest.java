package com.assignment.fulfilment.reconciliation.dto;

public record ResolveMismatchRequest(
        String action,
        String notes
) {
    public ResolveMismatchRequest {
        if (action == null || action.isBlank()) {
            action = "RESOLVE";
        } else {
            action = action.trim().toUpperCase();
        }
    }
}
