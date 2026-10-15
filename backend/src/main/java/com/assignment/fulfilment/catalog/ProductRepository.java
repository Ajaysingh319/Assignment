package com.assignment.fulfilment.catalog;

import com.assignment.fulfilment.catalog.domain.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySku(String sku);

    Optional<Product> findBySku(String sku);
}
