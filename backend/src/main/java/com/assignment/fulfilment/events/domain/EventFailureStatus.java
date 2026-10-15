package com.assignment.fulfilment.events.domain;

public enum EventFailureStatus {
    RETRYING,
    DEAD_LETTERED,
    REPLAYED,
    RESOLVED
}
