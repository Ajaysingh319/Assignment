package com.assignment.fulfilment.catalog;

import com.assignment.fulfilment.catalog.domain.Product;
import com.assignment.fulfilment.catalog.domain.Warehouse;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.catalog.dto.UpdateProductRequest;
import com.assignment.fulfilment.catalog.dto.UpdateWarehouseRequest;
import com.assignment.fulfilment.common.exception.DuplicateIdentifierException;
import com.assignment.fulfilment.inventory.InventoryRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryRepository inventoryRepository;

    public CatalogService(
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository,
            InventoryRepository inventoryRepository
    ) {
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        String sku = request.sku().trim();
        if (productRepository.existsBySku(sku)) {
            throw new DuplicateIdentifierException("sku", sku);
        }
        boolean active = request.active() == null || request.active();
        Product product = new Product(sku, request.name().trim(), trimToNull(request.description()), active);
        try {
            return ProductResponse.from(productRepository.saveAndFlush(product));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateIdentifierException("sku", sku);
        }
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listProducts() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));
        boolean active = request.active() == null || request.active();
        if (!active && product.isActive() && inventoryRepository.hasReservedQtyForProduct(id)) {
            throw new IllegalStateException("Cannot deactivate product with active reserved inventory. Fulfil or release reservations first.");
        }
        product.update(request.name().trim(), trimToNull(request.description()), active);
        return ProductResponse.from(productRepository.saveAndFlush(product));
    }

    @Transactional
    public ProductResponse deactivateProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));
        if (inventoryRepository.hasReservedQtyForProduct(id)) {
            throw new IllegalStateException("Cannot deactivate product with active reserved inventory. Fulfil or release reservations first.");
        }
        product.setActive(false);
        return ProductResponse.from(productRepository.saveAndFlush(product));
    }

    @Transactional
    public void deleteProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));
        if (inventoryRepository.existsByProductId(id)) {
            throw new IllegalStateException("Cannot delete product referenced by existing inventory. Deactivate it instead.");
        }
        try {
            productRepository.delete(product);
            productRepository.flush();
        } catch (DataIntegrityViolationException dive) {
            throw new IllegalStateException("Cannot delete product referenced by existing orders or reservations. Deactivate it instead.");
        }
    }

    @Transactional
    public WarehouseResponse createWarehouse(CreateWarehouseRequest request) {
        String code = request.code().trim();
        if (warehouseRepository.existsByCode(code)) {
            throw new DuplicateIdentifierException("code", code);
        }
        boolean active = request.active() == null || request.active();
        Warehouse warehouse = new Warehouse(code, request.name().trim(), trimToNull(request.location()), active);
        try {
            return WarehouseResponse.from(warehouseRepository.saveAndFlush(warehouse));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateIdentifierException("code", code);
        }
    }

    @Transactional(readOnly = true)
    public List<WarehouseResponse> listWarehouses() {
        return warehouseRepository.findAll().stream()
                .map(WarehouseResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouse(UUID id) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Warehouse not found: " + id));
        return WarehouseResponse.from(warehouse);
    }

    @Transactional
    public WarehouseResponse updateWarehouse(UUID id, UpdateWarehouseRequest request) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Warehouse not found: " + id));
        boolean active = request.active() == null || request.active();
        if (!active && warehouse.isActive() && inventoryRepository.hasReservedQtyForWarehouse(id)) {
            throw new IllegalStateException("Cannot deactivate warehouse with active reserved inventory. Fulfil or release reservations first.");
        }
        warehouse.update(request.name().trim(), trimToNull(request.location()), active);
        return WarehouseResponse.from(warehouseRepository.saveAndFlush(warehouse));
    }

    @Transactional
    public WarehouseResponse deactivateWarehouse(UUID id) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Warehouse not found: " + id));
        if (inventoryRepository.hasReservedQtyForWarehouse(id)) {
            throw new IllegalStateException("Cannot deactivate warehouse with active reserved inventory. Fulfil or release reservations first.");
        }
        warehouse.setActive(false);
        return WarehouseResponse.from(warehouseRepository.saveAndFlush(warehouse));
    }

    @Transactional
    public void deleteWarehouse(UUID id) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Warehouse not found: " + id));
        if (inventoryRepository.existsByWarehouseId(id)) {
            throw new IllegalStateException("Cannot delete warehouse referenced by existing inventory. Deactivate it instead.");
        }
        try {
            warehouseRepository.delete(warehouse);
            warehouseRepository.flush();
        } catch (DataIntegrityViolationException dive) {
            throw new IllegalStateException("Cannot delete warehouse referenced by existing reservations. Deactivate it instead.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
