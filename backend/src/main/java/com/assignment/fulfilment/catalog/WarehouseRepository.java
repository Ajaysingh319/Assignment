package com.assignment.fulfilment.catalog;

import com.assignment.fulfilment.catalog.domain.Warehouse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    boolean existsByCode(String code);

    Optional<Warehouse> findByCode(String code);
}
