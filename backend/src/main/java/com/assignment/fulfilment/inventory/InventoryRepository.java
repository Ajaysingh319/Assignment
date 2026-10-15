package com.assignment.fulfilment.inventory;

import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, InventoryId> {

    @Query("select i from Inventory i order by i.id.productId, i.id.warehouseId")
    List<Inventory> findAllOrdered();

    List<Inventory> findByIdProductIdOrderByIdWarehouseIdAsc(UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.id.productId = :productId order by i.id.warehouseId asc")
    List<Inventory> findForUpdateByProductId(@Param("productId") UUID productId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Inventory i
            set i.availableQty = i.availableQty - :quantity,
                i.reservedQty = i.reservedQty + :quantity,
                i.updatedAt = :now
            where i.id.productId = :productId
              and i.id.warehouseId = :warehouseId
              and i.availableQty >= :quantity
            """)
    int reserveStock(
            @Param("productId") UUID productId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") long quantity,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Inventory i
            set i.availableQty = i.availableQty + :quantity,
                i.reservedQty = i.reservedQty - :quantity,
                i.updatedAt = :now
            where i.id.productId = :productId
              and i.id.warehouseId = :warehouseId
              and i.reservedQty >= :quantity
            """)
    int releaseStock(
            @Param("productId") UUID productId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") long quantity,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Inventory i
            set i.reservedQty = i.reservedQty - :quantity,
                i.fulfilledQty = i.fulfilledQty + :quantity,
                i.updatedAt = :now
            where i.id.productId = :productId
              and i.id.warehouseId = :warehouseId
              and i.reservedQty >= :quantity
            """)
    int fulfilStock(
            @Param("productId") UUID productId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") long quantity,
            @Param("now") Instant now
    );
}
