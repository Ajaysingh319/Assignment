package com.assignment.fulfilment.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
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
class OrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ObjectMapper objectMapper;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("API: Order creation, idempotency, and lifecycle transitions")
    void testOrderApiFullFlow() throws Exception {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-API-1-" + u, "Api Order Product", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-API-1-" + u, "Api Order WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 20L));

        String idempotencyKey = "api-order-key-" + UUID.randomUUID();
        String createPayload = """
                {
                  "currency": "USD",
                  "items": [
                    {
                      "productId": "%s",
                      "quantity": 3,
                      "unitPrice": 15.50
                    }
                  ]
                }
                """.formatted(product.id());

        // 1. Missing Idempotency-Key header -> 400 Bad Request
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // 2. Successful order creation with Idempotency-Key header -> 201 Created
        String responseJson = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(46.50))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.reservations[0].quantity").value(3))
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(responseJson).get("id").asText();
        assertThat(orderId).isNotBlank();

        // 3. Same key + same payload -> returns original order
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        // 4. Same key + different payload -> 409 Conflict
        String conflictingPayload = """
                {
                  "currency": "USD",
                  "items": [
                    {
                      "productId": "%s",
                      "quantity": 5,
                      "unitPrice": 15.50
                    }
                  ]
                }
                """.formatted(product.id());

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictingPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already used with a different request payload")));

        // 5. GET /api/orders/{orderId}
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        // 6. GET /api/orders
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 7. Invalid transition: fulfil a PENDING_PAYMENT order -> 409 Conflict
        mockMvc.perform(post("/api/orders/" + orderId + "/fulfil"))
                .andExpect(status().isConflict());

        // 8. Confirm order -> 200 OK
        mockMvc.perform(post("/api/orders/" + orderId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        // 9. Fulfil confirmed order -> 200 OK
        mockMvc.perform(post("/api/orders/" + orderId + "/fulfil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.fulfilledAt").isNotEmpty());

        // 10. Repeated fulfilment is safe/idempotent -> 200 OK
        mockMvc.perform(post("/api/orders/" + orderId + "/fulfil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));
    }

    @Test
    @DisplayName("API: Cancel and Expiry endpoints")
    void testOrderApiCancelAndExpiry() throws Exception {
        String u = uid();
        ProductResponse product = catalogService.createProduct(
                new CreateProductRequest("SKU-ORD-API-2-" + u, "Api Order Product 2", null, true)
        );
        WarehouseResponse warehouse = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-ORD-API-2-" + u, "Api Order WH 2", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(product.id(), warehouse.id(), 20L));

        // Create order 1 for cancellation test
        String key1 = "api-cancel-key-" + UUID.randomUUID();
        String payload1 = """
                {
                  "items": [
                    { "productId": "%s", "quantity": 2 }
                  ]
                }
                """.formatted(product.id());

        String res1 = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", key1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId1 = objectMapper.readTree(res1).get("id").asText();

        // Cancel order 1 -> 200 OK
        mockMvc.perform(post("/api/orders/" + orderId1 + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        // Repeated cancel is safe -> 200 OK
        mockMvc.perform(post("/api/orders/" + orderId1 + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Create order 2 for expiry test
        String key2 = "api-expiry-key-" + UUID.randomUUID();
        String res2 = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", key2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId2 = objectMapper.readTree(res2).get("id").asText();

        // Expire order 2 -> 200 OK
        mockMvc.perform(post("/api/orders/" + orderId2 + "/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    }
}
