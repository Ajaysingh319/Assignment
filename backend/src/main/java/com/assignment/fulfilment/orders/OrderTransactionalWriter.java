package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.catalog.ProductRepository;
import com.assignment.fulfilment.catalog.domain.Product;
import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.inventory.InventoryReservationService;
import com.assignment.fulfilment.inventory.dto.OrderItemRequest;
import com.assignment.fulfilment.inventory.dto.ReservationItemResponse;
import com.assignment.fulfilment.inventory.dto.ReservationResponse;
import com.assignment.fulfilment.inventory.dto.ReserveInventoryRequest;
import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderItem;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderItemResponse;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderTransactionalWriter {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryReservationService inventoryReservationService;
    private final AuditLogRepository auditLogRepository;

    public OrderTransactionalWriter(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            InventoryReservationService inventoryReservationService,
            AuditLogRepository auditLogRepository
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.inventoryReservationService = inventoryReservationService;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createOrderInTransaction(
            UUID orderId,
            String idempotencyKey,
            String requestHash,
            CreateOrderRequest request
    ) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        List<OrderItemRequest> reservationRequests = new ArrayList<>();

        for (CreateOrderItemRequest item : request.items()) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new NoSuchElementException("Product not found: " + item.productId()));

            if (!product.isActive()) {
                throw new IllegalArgumentException("Product is inactive: " + product.getSku());
            }

            BigDecimal unitPrice = item.unitPrice() != null ? item.unitPrice() : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
            totalAmount = totalAmount.add(lineTotal);

            OrderItem orderItem = new OrderItem(item.productId(), item.quantity(), unitPrice, lineTotal);
            orderItems.add(orderItem);
            reservationRequests.add(new OrderItemRequest(item.productId(), item.quantity()));
        }

        // Reserve inventory. If stock is insufficient, throws InsufficientStockException and rolls back entire transaction.
        ReservationResponse reservationResponse = inventoryReservationService.reserve(
                new ReserveInventoryRequest(orderId, reservationRequests)
        );

        Order order = new Order(orderId, idempotencyKey, requestHash, request.currency(), totalAmount);
        for (OrderItem item : orderItems) {
            order.addItem(item);
        }

        Order saved = orderRepository.save(order);
        orderRepository.flush();

        auditLogRepository.save(new AuditLog(
                "order",
                saved.getId().toString(),
                "ORDER_CREATED",
                "SYSTEM",
                "idempotencyKey=" + idempotencyKey + ", totalAmount=" + totalAmount + ", currency=" + request.currency()
        ));

        List<OrderItemResponse> itemResponses = saved.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getLineTotal()
                ))
                .toList();

        return new OrderResponse(
                saved.getId(),
                saved.getStatus(),
                saved.getIdempotencyKey(),
                saved.getCurrency(),
                saved.getTotalAmount(),
                itemResponses,
                reservationResponse.items(),
                saved.getCreatedAt(),
                saved.getUpdatedAt(),
                saved.getConfirmedAt(),
                saved.getCancelledAt(),
                saved.getFulfilledAt()
        );
    }
}
