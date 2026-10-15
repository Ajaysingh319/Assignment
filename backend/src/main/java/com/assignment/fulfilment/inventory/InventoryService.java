package com.assignment.fulfilment.inventory;

import com.assignment.fulfilment.catalog.ProductRepository;
import com.assignment.fulfilment.catalog.WarehouseRepository;
import com.assignment.fulfilment.catalog.domain.Product;
import com.assignment.fulfilment.catalog.domain.Warehouse;
import com.assignment.fulfilment.common.audit.AuditLog;
import com.assignment.fulfilment.common.audit.AuditLogRepository;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.inventory.dto.InventoryListResponse;
import com.assignment.fulfilment.inventory.dto.ProductInventoryResponse;
import com.assignment.fulfilment.inventory.dto.WarehouseInventoryResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final AuditLogRepository auditLogRepository;

    public InventoryService(
            InventoryRepository inventoryRepository,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository,
            AuditLogRepository auditLogRepository
    ) {
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public InventoryListResponse listGrouped() {
        List<Inventory> rows = inventoryRepository.findAllOrdered();
        Map<UUID, Product> products = productRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, product -> product));
        Map<UUID, Warehouse> warehouses = warehouseRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Warehouse::getId, warehouse -> warehouse));

        Map<UUID, List<WarehouseInventoryResponse>> grouped = new LinkedHashMap<>();
        for (Inventory row : rows) {
            Warehouse warehouse = warehouses.get(row.getId().getWarehouseId());
            grouped.computeIfAbsent(row.getId().getProductId(), ignored -> new ArrayList<>())
                    .add(new WarehouseInventoryResponse(
                            warehouse.getId(),
                            warehouse.getCode(),
                            warehouse.getName(),
                            row.getAvailableQty(),
                            row.getReservedQty(),
                            row.getFulfilledQty(),
                            row.trackedTotal(),
                            row.getUpdatedAt()
                    ));
        }

        List<ProductInventoryResponse> items = grouped.entrySet().stream()
                .map(entry -> {
                    Product product = products.get(entry.getKey());
                    return new ProductInventoryResponse(
                            product.getId(),
                            product.getSku(),
                            product.getName(),
                            List.copyOf(entry.getValue())
                    );
                })
                .toList();
        return new InventoryListResponse(items);
    }

    @Transactional
    public InventoryListResponse adjust(AdjustInventoryRequest request) {
        if (request.availableDelta() == 0) {
            throw new IllegalArgumentException("availableDelta must not be zero");
        }
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new NoSuchElementException("Product not found"));
        Warehouse warehouse = warehouseRepository.findById(request.warehouseId())
                .orElseThrow(() -> new NoSuchElementException("Warehouse not found"));

        InventoryId id = new InventoryId(product.getId(), warehouse.getId());
        Inventory inventory = inventoryRepository.findById(id)
                .orElseGet(() -> {
                    if (request.availableDelta() < 0) {
                        throw new IllegalArgumentException("available quantity cannot be negative");
                    }
                    return new Inventory(id, 0);
                });
        inventory.adjustAvailable(request.availableDelta());
        inventoryRepository.saveAndFlush(inventory);
        auditLogRepository.save(new AuditLog(
                "inventory",
                product.getId() + ":" + warehouse.getId(),
                "STOCK_ADJUSTMENT",
                "OPERATOR",
                "availableDelta=" + request.availableDelta()
        ));
        return listGrouped();
    }
}
