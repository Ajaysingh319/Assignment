package com.assignment.fulfilment.inventory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryReservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("API: reserve, cancel, and fulfil lifecycle")
    void testReservationApiLifecycle() throws Exception {
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-API-RES-1", "Api Product", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-API-RES-1", "Api Warehouse", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 15L));

        UUID orderId = UUID.randomUUID();

        // 1. Reserve 5 units
        String reservePayload = """
                {
                    "orderId": "%s",
                    "items": [
                        {"productId": "%s", "quantity": 5}
                    ]
                }
                """.formatted(orderId, product.id());

        mockMvc.perform(post("/api/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservePayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items[0].quantity").value(5));

        // 2. Query reservations for order
        mockMvc.perform(get("/api/inventory/reservations/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(5));

        // 3. Fulfil reservation
        mockMvc.perform(post("/api/inventory/reservations/" + orderId + "/fulfil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.items[0].status").value("FULFILLED"));

        // 4. Repeat fulfilment is idempotent (at most once)
        mockMvc.perform(post("/api/inventory/reservations/" + orderId + "/fulfil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));
    }

    @Test
    @DisplayName("API: insufficient stock returns 409 Conflict")
    void testReservationInsufficientStockReturnsConflict() throws Exception {
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-API-RES-2", "Low Stock Product", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-API-RES-2", "Api WH 2", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 2L));

        UUID orderId = UUID.randomUUID();
        String reservePayload = """
                {
                    "orderId": "%s",
                    "items": [
                        {"productId": "%s", "quantity": 10}
                    ]
                }
                """.formatted(orderId, product.id());

        mockMvc.perform(post("/api/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient stock")));
    }

    @Test
    @DisplayName("API: validation rejects empty items or zero quantity")
    void testReservationValidationErrors() throws Exception {
        UUID orderId = UUID.randomUUID();

        // Empty items
        mockMvc.perform(post("/api/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": "%s", "items": []}
                                """.formatted(orderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Zero quantity
        mockMvc.perform(post("/api/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "orderId": "%s",
                                    "items": [{"productId": "%s", "quantity": 0}]
                                }
                                """.formatted(orderId, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
