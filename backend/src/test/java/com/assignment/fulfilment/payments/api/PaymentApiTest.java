package com.assignment.fulfilment.payments.api;

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
import com.assignment.fulfilment.orders.OrderService;
import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import com.assignment.fulfilment.orders.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
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
class PaymentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

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
    @DisplayName("API: Simulate payment, callbacks, and payment query endpoints")
    void testPaymentApiLifecycle() throws Exception {
        String u = uid();
        ProductResponse p = catalogService.createProduct(
                new CreateProductRequest("SKU-PAY-API-" + u, "Api Payment Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-PAY-API-" + u, "Api Payment WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(p.id(), wh.id(), 20L));

        OrderResponse order = orderService.createOrder(
                "idemp-pay-api-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(p.id(), 2L, new BigDecimal("30.00"))))
        );

        // 1. Invalid outcome -> 400 Bad Request
        String invalidPayload = """
                {
                  "outcome": "INVALID_OUTCOME"
                }
                """;
        mockMvc.perform(post("/api/orders/" + order.id() + "/payments/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());

        // 2. Simulate payment: TIMED_OUT -> 201 Created
        String simulatePayload = """
                {
                  "outcome": "TIMED_OUT",
                  "amount": 60.00,
                  "currency": "USD"
                }
                """;
        String simulateRes = mockMvc.perform(post("/api/orders/" + order.id() + "/payments/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulatePayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TIMED_OUT"))
                .andExpect(jsonPath("$.amount").value(60.00))
                .andExpect(jsonPath("$.providerReference").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String paymentId = objectMapper.readTree(simulateRes).get("id").asText();
        String providerRef = objectMapper.readTree(simulateRes).get("providerReference").asText();

        // 3. GET /api/payments/{paymentId} -> 200 OK
        mockMvc.perform(get("/api/payments/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.status").value("TIMED_OUT"));

        // 4. GET /api/payments -> 200 OK
        mockMvc.perform(get("/api/payments?orderId=" + order.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(paymentId));

        // 5. POST /api/payments/callbacks -> 200 OK (delayed success callback)
        String callbackPayload = """
                {
                  "callbackId": "cb-api-%s",
                  "paymentId": "%s",
                  "providerReference": "%s",
                  "result": "SUCCEEDED"
                }
                """.formatted(UUID.randomUUID(), paymentId, providerRef);

        mockMvc.perform(post("/api/payments/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.succeededAt").isNotEmpty());

        // 6. Verify order confirmed
        mockMvc.perform(get("/api/orders/" + order.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
}
