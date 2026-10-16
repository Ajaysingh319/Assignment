package com.assignment.fulfilment.catalog.seed;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.ProductRepository;
import com.assignment.fulfilment.catalog.WarehouseRepository;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.inventory.InventoryRepository;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import java.util.Map;
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
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;

    public DevCatalogSeeder(
            CatalogService catalogService,
            InventoryService inventoryService,
            InventoryRepository inventoryRepository,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository
    ) {
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. Seed Warehouses
        WarehouseResponse east = getOrCreateWarehouse("WH-EAST", "East Warehouse", "New York, USA");
        WarehouseResponse west = getOrCreateWarehouse("WH-WEST", "West Warehouse", "California, USA");

        // 2. Seed Products
        ProductResponse redWidget = getOrCreateProduct("WIDGET-RED", "Red Widget", "Standard red widget component");
        ProductResponse blueWidget = getOrCreateProduct("WIDGET-BLUE", "Blue Widget", "Precision blue widget component");
        ProductResponse greenWidget = getOrCreateProduct("WIDGET-GREEN", "Green Widget", "Eco-grade green widget component");
        ProductResponse premiumWidget = getOrCreateProduct("WIDGET-PREMIUM", "Premium Widget", "High-durability titanium widget");
        ProductResponse blackWidget = getOrCreateProduct("WIDGET-BLACK", "Black Widget", "Industrial black widget assembly");

        // 3. Seed Realistic Varied Inventory Allocations (idempotent: only if row does not exist yet)
        Map<ProductResponse, Map<WarehouseResponse, Long>> stockPlan = Map.of(
                redWidget, Map.of(east, 25L, west, 15L),
                blueWidget, Map.of(east, 40L, west, 30L),
                greenWidget, Map.of(east, 10L, west, 20L),
                premiumWidget, Map.of(east, 5L, west, 8L),
                blackWidget, Map.of(east, 50L, west, 45L)
        );

        for (var productEntry : stockPlan.entrySet()) {
            ProductResponse product = productEntry.getKey();
            for (var warehouseEntry : productEntry.getValue().entrySet()) {
                WarehouseResponse warehouse = warehouseEntry.getKey();
                Long qty = warehouseEntry.getValue();
                InventoryId id = new InventoryId(product.id(), warehouse.id());
                if (!inventoryRepository.existsById(id)) {
                    inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), qty));
                }
            }
        }

        log.info("Development catalogue seed applied: 5 products, 2 warehouses, and 10 inventory records (app.seed.enabled=true)");
    }

    private ProductResponse getOrCreateProduct(String sku, String name, String description) {
        return productRepository.findBySku(sku)
                .map(ProductResponse::from)
                .orElseGet(() -> catalogService.createProduct(new CreateProductRequest(sku, name, description, true)));
    }

    private WarehouseResponse getOrCreateWarehouse(String code, String name, String location) {
        return warehouseRepository.findByCode(code)
                .map(WarehouseResponse::from)
                .orElseGet(() -> catalogService.createWarehouse(new CreateWarehouseRequest(code, name, location, true)));
    }
}
