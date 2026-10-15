package com.assignment.fulfilment.catalog.seed;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.ProductRepository;
import com.assignment.fulfilment.catalog.WarehouseRepository;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DevCatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevCatalogSeeder.class);

    private final CatalogService catalogService;
    private final InventoryService inventoryService;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;

    public DevCatalogSeeder(
            CatalogService catalogService,
            InventoryService inventoryService,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository
    ) {
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        ProductResponse widget = productRepository.findBySku("WIDGET-RED")
                .map(ProductResponse::from)
                .orElseGet(() -> catalogService.createProduct(
                        new CreateProductRequest("WIDGET-RED", "Red widget", "Development seed product", true)
                ));
        WarehouseResponse east = warehouseRepository.findByCode("WH-EAST")
                .map(WarehouseResponse::from)
                .orElseGet(() -> catalogService.createWarehouse(
                        new CreateWarehouseRequest("WH-EAST", "East warehouse", "Development", true)
                ));
        if (inventoryService.listGrouped().items().isEmpty()) {
            inventoryService.adjust(new AdjustInventoryRequest(widget.id(), east.id(), 10L));
        }
        log.info("Development catalogue seed applied (app.seed.enabled=true)");
    }
}
