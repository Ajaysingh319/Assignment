package com.assignment.fulfilment.catalog.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.fulfilment.catalog.ProductRepository;
import com.assignment.fulfilment.catalog.WarehouseRepository;
import com.assignment.fulfilment.catalog.domain.Product;
import com.assignment.fulfilment.catalog.domain.Warehouse;
import com.assignment.fulfilment.inventory.InventoryRepository;
import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.seed.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class DevCatalogSeederTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void explicitDevSeedPopulatesProductWarehouseAndInventory() {
        Optional<Product> product = productRepository.findBySku("WIDGET-RED");
        assertThat(product).isPresent();
        assertThat(product.get().getName()).isEqualTo("Red widget");

        Optional<Warehouse> warehouse = warehouseRepository.findByCode("WH-EAST");
        assertThat(warehouse).isPresent();
        assertThat(warehouse.get().getName()).isEqualTo("East warehouse");

        InventoryId id = new InventoryId(product.get().getId(), warehouse.get().getId());
        Optional<Inventory> inventory = inventoryRepository.findById(id);
        assertThat(inventory).isPresent();
        assertThat(inventory.get().getAvailableQty()).isEqualTo(10L);
        assertThat(inventory.get().trackedTotal()).isEqualTo(10L);
    }
}
