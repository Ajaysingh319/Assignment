package com.assignment.fulfilment.orders.api;

import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.domain.OrderRequestOutcome;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderRequestResponse;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        OrderResponse response = orderService.createOrder(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> listOrders(
            @RequestParam(name = "status", required = false) OrderStatus status
    ) {
        return ResponseEntity.ok(orderService.listOrders(status));
    }

    /**
     * Operation history of order-creation requests, including rejected requests that never became orders.
     */
    @GetMapping("/requests")
    public ResponseEntity<List<OrderRequestResponse>> listRequests(
            @RequestParam(name = "outcome", required = false) OrderRequestOutcome outcome,
            @RequestParam(name = "keyPrefix", required = false) String keyPrefix,
            @RequestParam(name = "limit", defaultValue = "200") int limit
    ) {
        return ResponseEntity.ok(orderService.listRequests(outcome, keyPrefix, limit));
    }

    @GetMapping("/requests/summary")
    public ResponseEntity<Map<String, Long>> requestSummary(
            @RequestParam(name = "keyPrefix", required = false) String keyPrefix
    ) {
        return ResponseEntity.ok(orderService.requestOutcomeCounts(keyPrefix));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable("orderId") UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<OrderResponse> confirmOrder(@PathVariable("orderId") UUID orderId) {
        return ResponseEntity.ok(orderService.confirmOrder(orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable("orderId") UUID orderId) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId));
    }

    @PostMapping("/{orderId}/fulfil")
    public ResponseEntity<OrderResponse> fulfilOrder(@PathVariable("orderId") UUID orderId) {
        return ResponseEntity.ok(orderService.fulfilOrder(orderId));
    }

    @PostMapping("/{orderId}/expire")
    public ResponseEntity<OrderResponse> expireOrder(@PathVariable("orderId") UUID orderId) {
        return ResponseEntity.ok(orderService.expireOrder(orderId));
    }
}
