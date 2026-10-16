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
        // Assert 5 products
        assertThat(productRepository.findBySku("WIDGET-RED")).isPresent()
                .hasValueSatisfying(p -> assertThat(p.getName()).isEqualTo("Red Widget"));
        assertThat(productRepository.findBySku("WIDGET-BLUE")).isPresent()
                .hasValueSatisfying(p -> assertThat(p.getName()).isEqualTo("Blue Widget"));
        assertThat(productRepository.findBySku("WIDGET-GREEN")).isPresent()
                .hasValueSatisfying(p -> assertThat(p.getName()).isEqualTo("Green Widget"));
        assertThat(productRepository.findBySku("WIDGET-PREMIUM")).isPresent()
                .hasValueSatisfying(p -> assertThat(p.getName()).isEqualTo("Premium Widget"));
        assertThat(productRepository.findBySku("WIDGET-BLACK")).isPresent()
                .hasValueSatisfying(p -> assertThat(p.getName()).isEqualTo("Black Widget"));

        // Assert 2 warehouses
        Warehouse east = warehouseRepository.findByCode("WH-EAST").orElseThrow();
        Warehouse west = warehouseRepository.findByCode("WH-WEST").orElseThrow();
        assertThat(east.getName()).isEqualTo("East Warehouse");
        assertThat(west.getName()).isEqualTo("West Warehouse");

        // Assert inventory records for each product in both warehouses
        Product red = productRepository.findBySku("WIDGET-RED").orElseThrow();
        Inventory redEast = inventoryRepository.findById(new InventoryId(red.getId(), east.getId())).orElseThrow();
        Inventory redWest = inventoryRepository.findById(new InventoryId(red.getId(), west.getId())).orElseThrow();
        assertThat(redEast.getAvailableQty()).isEqualTo(25L);
        assertThat(redWest.getAvailableQty()).isEqualTo(15L);

        Product black = productRepository.findBySku("WIDGET-BLACK").orElseThrow();
        Inventory blackEast = inventoryRepository.findById(new InventoryId(black.getId(), east.getId())).orElseThrow();
        Inventory blackWest = inventoryRepository.findById(new InventoryId(black.getId(), west.getId())).orElseThrow();
        assertThat(blackEast.getAvailableQty()).isEqualTo(50L);
        assertThat(blackWest.getAvailableQty()).isEqualTo(45L);
    }
}
