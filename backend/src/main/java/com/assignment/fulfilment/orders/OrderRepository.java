package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.orders.domain.Order;
import com.assignment.fulfilment.orders.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = {"items"})
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"items"})
    @Override
    Optional<Order> findById(UUID id);

    @EntityGraph(attributePaths = {"items"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"items"})
    List<Order> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"items"})
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :before ORDER BY o.createdAt ASC")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") OrderStatus status, @Param("before") java.time.Instant before);
}
