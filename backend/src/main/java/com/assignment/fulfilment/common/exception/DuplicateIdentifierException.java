package com.assignment.fulfilment.common.exception;

public class DuplicateIdentifierException extends RuntimeException {

    private final String field;
    private final String value;

    public DuplicateIdentifierException(String field, String value) {
        super(field + " already exists: " + value);
        this.field = field;
        this.value = value;
    }

    public String getField() {
        return field;
    }

    public String getValue() {
        return value;
    }
}
