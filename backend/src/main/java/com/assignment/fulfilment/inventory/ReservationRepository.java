package com.assignment.fulfilment.inventory;

import com.assignment.fulfilment.inventory.domain.Reservation;
import com.assignment.fulfilment.inventory.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.orderId = :orderId")
    List<Reservation> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    List<Reservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    List<Reservation> findByProductId(UUID productId);

    List<Reservation> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);
}

