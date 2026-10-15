package com.assignment.fulfilment.common.web;

import com.assignment.fulfilment.common.api.ApiError;
import com.assignment.fulfilment.common.exception.DuplicateIdentifierException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<String> details = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.toList());
        return error(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body", request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NoSuchElementException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(
            IllegalStateException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateIdentifierException.class)
    public ResponseEntity<ApiError> handleDuplicate(
            DuplicateIdentifierException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request,
                List.of(exception.getField() + " must be unique")
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        String cause = exception.getMostSpecificCause().getMessage();
        String text = cause == null ? "" : cause.toLowerCase();
        if (text.contains("uk_products_sku") || text.contains("sku")) {
            return error(HttpStatus.CONFLICT, "sku already exists", request, List.of("sku must be unique"));
        }
        if (text.contains("uk_warehouses_code") || text.contains("code")) {
            return error(HttpStatus.CONFLICT, "code already exists", request, List.of("code must be unique"));
        }
        if (text.contains("chk_inventory") || text.contains("nonnegative")) {
            return error(HttpStatus.BAD_REQUEST, "inventory quantities must be nonnegative", request, List.of());
        }
        return error(HttpStatus.CONFLICT, "Data integrity violation", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unhandled error", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<String> details
    ) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_KEY),
                details
        );
        return ResponseEntity.status(status).body(body);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
