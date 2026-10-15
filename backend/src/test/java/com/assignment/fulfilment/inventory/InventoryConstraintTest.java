package com.assignment.fulfilment.inventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assignment.fulfilment.inventory.domain.Inventory;
import com.assignment.fulfilment.inventory.domain.InventoryId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InventoryConstraintTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void domainRejectsNegativeAvailableQuantity() {
        Inventory inventory = new Inventory(new InventoryId(UUID.randomUUID(), UUID.randomUUID()), 1);
        assertThatThrownBy(() -> inventory.adjustAvailable(-2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void adjustmentCannotDriveAvailableNegativeAndListIsGrouped() throws Exception {
        MvcResult product = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-INV\",\"name\":\"Bolt\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult warehouse = mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-INV\",\"name\":\"West\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode productJson = objectMapper.readTree(product.getResponse().getContentAsString());
        JsonNode warehouseJson = objectMapper.readTree(warehouse.getResponse().getContentAsString());
        String productId = productJson.get("id").asText();
        String warehouseId = warehouseJson.get("id").asText();

        mockMvc.perform(post("/api/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","warehouseId":"%s","availableDelta":5}
                                """.formatted(productId, warehouseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.sku == 'SKU-INV')].warehouses[0].warehouseCode").value("WH-INV"))
                .andExpect(jsonPath("$.items[?(@.sku == 'SKU-INV')].warehouses[0].availableQty").value(5))
                .andExpect(jsonPath("$.items[?(@.sku == 'SKU-INV')].warehouses[0].trackedTotal").value(5));

        mockMvc.perform(post("/api/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","warehouseId":"%s","availableDelta":-6}
                                """.formatted(productId, warehouseId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.sku == 'SKU-INV')].warehouses[0].availableQty").value(5));
    }

    @Test
    void databaseRejectsNegativeInventoryQuantities() throws Exception {
        MvcResult product = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-CHK\",\"name\":\"Nut\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult warehouse = mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-CHK\",\"name\":\"North\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID productId = UUID.fromString(objectMapper.readTree(product.getResponse().getContentAsString()).get("id").asText());
        UUID warehouseId = UUID.fromString(objectMapper.readTree(warehouse.getResponse().getContentAsString()).get("id").asText());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO inventory (product_id, warehouse_id, available_qty, reserved_qty, fulfilled_qty, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                productId,
                warehouseId,
                -1,
                0,
                0,
                0
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO inventory (product_id, warehouse_id, available_qty, reserved_qty, fulfilled_qty, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                productId,
                warehouseId,
                0,
                -1,
                0,
                0
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO inventory (product_id, warehouse_id, available_qty, reserved_qty, fulfilled_qty, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                productId,
                warehouseId,
                0,
                0,
                -1,
                0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adjustmentRejectsZeroDelta() throws Exception {
        MvcResult product = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-ZERO\",\"name\":\"Zero Product\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult warehouse = mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-ZERO\",\"name\":\"Zero WH\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = objectMapper.readTree(product.getResponse().getContentAsString()).get("id").asText();
        String warehouseId = objectMapper.readTree(warehouse.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","warehouseId":"%s","availableDelta":0}
                                """.formatted(productId, warehouseId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("availableDelta must not be zero"));
    }

    @Test
    void adjustmentForUnknownProductOrWarehouseReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","warehouseId":"%s","availableDelta":5}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
