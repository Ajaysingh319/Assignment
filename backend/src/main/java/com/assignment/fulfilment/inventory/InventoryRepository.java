package com.assignment.fulfilment.inventory;

import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InventoryRepository extends JpaRepository<Inventory, InventoryId> {

    @Query("select i from Inventory i order by i.id.productId, i.id.warehouseId")
    List<Inventory> findAllOrdered();

    List<Inventory> findByIdProductIdOrderByIdWarehouseIdAsc(UUID productId);
}
