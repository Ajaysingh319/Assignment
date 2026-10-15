package com.assignment.fulfilment.inventory;

import com.assignment.fulfilment.catalog.ProductRepository;
import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.common.exception.InsufficientStockException;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import com.assignment.fulfilment.inventory.dto.OrderItemRequest;
import com.assignment.fulfilment.inventory.dto.ReservationItemResponse;
import com.assignment.fulfilment.inventory.dto.ReservationResponse;
import com.assignment.fulfilment.inventory.dto.ReserveInventoryRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryReservationService {

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final AuditLogRepository auditLogRepository;

    public InventoryReservationService(
            InventoryRepository inventoryRepository,
            ReservationRepository reservationRepository,
            ProductRepository productRepository,
            AuditLogRepository auditLogRepository
    ) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Reserves inventory for an order with multiple products across warehouses.
     * All-or-nothing: if any product line cannot be satisfied, rolls back the entire transaction.
     */
    @Transactional
    public ReservationResponse reserve(ReserveInventoryRequest request) {
        if (request.orderId() == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }

        // Check if reservations already exist for this order (idempotency)
        List<Reservation> existing = reservationRepository.findByOrderId(request.orderId());
        if (!existing.isEmpty()) {
            return new ReservationResponse(
                    request.orderId(),
                    determineAggregateStatus(existing),
                    existing.stream().map(ReservationItemResponse::from).toList()
            );
        }

        // Aggregate demands by product to handle duplicate lines in the request
        Map<UUID, Long> productDemands = new LinkedHashMap<>();
        for (OrderItemRequest item : request.items()) {
            if (item.productId() == null) {
                throw new IllegalArgumentException("productId must not be null");
            }
            if (item.quantity() <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            productDemands.merge(item.productId(), item.quantity(), Long::sum);
        }

        // Validate all products exist
        for (UUID productId : productDemands.keySet()) {
            if (!productRepository.existsById(productId)) {
                throw new NoSuchElementException("Product not found: " + productId);
            }
        }

        // Deterministic sorting of product IDs eliminates deadlocks between concurrent multi-item orders
        List<UUID> sortedProductIds = productDemands.keySet().stream().sorted().toList();

        List<Reservation> createdReservations = new ArrayList<>();

        for (UUID productId : sortedProductIds) {
            long totalNeeded = productDemands.get(productId);

            // Fetch candidate warehouses with pessimistic write lock ordered deterministically by warehouse_id
            List<Inventory> candidateInventories = inventoryRepository.findForUpdateByProductId(productId);

            long totalAvailable = candidateInventories.stream()
                    .mapToLong(Inventory::getAvailableQty)
                    .sum();

            if (totalAvailable < totalNeeded) {
                throw new InsufficientStockException(productId, totalNeeded, totalAvailable);
            }

            long remainingToAllocate = totalNeeded;
            for (Inventory inventory : candidateInventories) {
                long availableInWarehouse = inventory.getAvailableQty();
                if (availableInWarehouse <= 0) {
                    continue;
                }

                long toAllocate = Math.min(availableInWarehouse, remainingToAllocate);

                // Atomic conditional update at database level: WHERE available_qty >= :toAllocate
                int rowsUpdated = inventoryRepository.reserveStock(
                        productId,
                        inventory.getId().getWarehouseId(),
                        toAllocate,
                        Instant.now()
                );

                if (rowsUpdated != 1) {
                    throw new InsufficientStockException(productId, totalNeeded, 0);
                }

                Reservation reservation = new Reservation(
                        request.orderId(),
                        productId,
                        inventory.getId().getWarehouseId(),
                        toAllocate,
                        ReservationStatus.RESERVED
                );
                Reservation saved = reservationRepository.save(reservation);
                createdReservations.add(saved);

                auditLogRepository.save(new AuditLog(
                        "reservation",
                        saved.getId().toString(),
                        "STOCK_RESERVED",
                        "SYSTEM",
                        "orderId=" + request.orderId() + ", productId=" + productId +
                                ", warehouseId=" + inventory.getId().getWarehouseId() + ", qty=" + toAllocate
                ));

                remainingToAllocate -= toAllocate;
                if (remainingToAllocate == 0) {
                    break;
                }
            }

            if (remainingToAllocate > 0) {
                throw new InsufficientStockException(productId, totalNeeded, totalNeeded - remainingToAllocate);
            }
        }

        return new ReservationResponse(
                request.orderId(),
                "RESERVED",
                createdReservations.stream().map(ReservationItemResponse::from).toList()
        );
    }

    /**
     * Releases each reservation for the given order at most once.
     */
    @Transactional
    public ReservationResponse cancel(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }

        List<Reservation> reservations = reservationRepository.findByOrderIdForUpdate(orderId);
        if (reservations.isEmpty()) {
            throw new NoSuchElementException("No reservations found for order: " + orderId);
        }

        // Sort reservations deterministically by product and warehouse
        reservations.sort(Comparator.comparing(Reservation::getProductId)
                .thenComparing(Reservation::getWarehouseId));

        for (Reservation reservation : reservations) {
            // Only release reservations currently in RESERVED status (at most once)
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                int updated = inventoryRepository.releaseStock(
                        reservation.getProductId(),
                        reservation.getWarehouseId(),
                        reservation.getQuantity(),
                        Instant.now()
                );
                if (updated != 1) {
                    throw new IllegalStateException("Failed to release stock for reservation " + reservation.getId());
                }
                reservation.release();
                reservationRepository.save(reservation);

                auditLogRepository.save(new AuditLog(
                        "reservation",
                        reservation.getId().toString(),
                        "STOCK_RELEASED",
                        "SYSTEM",
                        "orderId=" + orderId + ", qty=" + reservation.getQuantity()
                ));
            }
        }

        return new ReservationResponse(
                orderId,
                determineAggregateStatus(reservations),
                reservations.stream().map(ReservationItemResponse::from).toList()
        );
    }

    /**
     * Moves reserved quantities to fulfilled quantities for the given order at most once.
     */
    @Transactional
    public ReservationResponse fulfil(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }

        List<Reservation> reservations = reservationRepository.findByOrderIdForUpdate(orderId);
        if (reservations.isEmpty()) {
            throw new NoSuchElementException("No reservations found for order: " + orderId);
        }

        reservations.sort(Comparator.comparing(Reservation::getProductId)
                .thenComparing(Reservation::getWarehouseId));

        for (Reservation reservation : reservations) {
            // Move reserved to fulfilled at most once
            if (reservation.getStatus() == ReservationStatus.RESERVED || reservation.getStatus() == ReservationStatus.CONFIRMED) {
                int updated = inventoryRepository.fulfilStock(
                        reservation.getProductId(),
                        reservation.getWarehouseId(),
                        reservation.getQuantity(),
                        Instant.now()
                );
                if (updated != 1) {
                    throw new IllegalStateException("Failed to fulfil stock for reservation " + reservation.getId());
                }
                reservation.fulfil();
                reservationRepository.save(reservation);

                auditLogRepository.save(new AuditLog(
                        "reservation",
                        reservation.getId().toString(),
                        "STOCK_FULFILLED",
                        "SYSTEM",
                        "orderId=" + orderId + ", qty=" + reservation.getQuantity()
                ));
            }
        }

        return new ReservationResponse(
                orderId,
                determineAggregateStatus(reservations),
                reservations.stream().map(ReservationItemResponse::from).toList()
        );
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservations(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        List<Reservation> reservations = reservationRepository.findByOrderId(orderId);
        if (reservations.isEmpty()) {
            throw new NoSuchElementException("No reservations found for order: " + orderId);
        }
        return new ReservationResponse(
                orderId,
                determineAggregateStatus(reservations),
                reservations.stream().map(ReservationItemResponse::from).toList()
        );
    }

    private static String determineAggregateStatus(List<Reservation> reservations) {
        if (reservations.stream().allMatch(r -> r.getStatus() == ReservationStatus.FULFILLED)) {
            return "FULFILLED";
        }
        if (reservations.stream().allMatch(r -> r.getStatus() == ReservationStatus.RELEASED)) {
            return "RELEASED";
        }
        if (reservations.stream().allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED)) {
            return "CONFIRMED";
        }
        return "RESERVED";
    }
}
