package com.assignment.fulfilment.catalog;

import com.assignment.fulfilment.catalog.domain.Product;
import com.assignment.fulfilment.catalog.domain.Warehouse;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.common.exception.DuplicateIdentifierException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;

    public CatalogService(ProductRepository productRepository, WarehouseRepository warehouseRepository) {
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
