package com.assignment.fulfilment.reconciliation.api;

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
import com.assignment.fulfilment.payments.PaymentRepository;
import com.assignment.fulfilment.payments.domain.Payment;
import com.assignment.fulfilment.payments.domain.PaymentStatus;
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
class ReconciliationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentRepository paymentRepository;

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
    @DisplayName("API: Run reconciliation, list records, get record, and resolve mismatch")
    void testReconciliationApiLifecycle() throws Exception {
        String u = uid();
        ProductResponse p = catalogService.createProduct(
                new CreateProductRequest("SKU-REC-API-" + u, "Api Rec Product", null, true)
        );
        WarehouseResponse wh = catalogService.createWarehouse(
                new CreateWarehouseRequest("WH-REC-API-" + u, "Api Rec WH", null, true)
        );
        inventoryService.adjust(new AdjustInventoryRequest(p.id(), wh.id(), 20L));

        OrderResponse order = orderService.createOrder(
                "idemp-rec-api-" + UUID.randomUUID(),
                new CreateOrderRequest("USD", List.of(new CreateOrderItemRequest(p.id(), 2L, new BigDecimal("40.00"))))
        );
        orderService.cancelOrder(order.id());

        Payment payment = new Payment(order.id(), "api-ref-" + UUID.randomUUID(), PaymentStatus.SUCCEEDED, new BigDecimal("80.00"), "USD");
        paymentRepository.save(payment);

        // 1. POST /api/reconciliation/run -> 200 OK
        String runJson = mockMvc.perform(post("/api/reconciliation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray())
                .andReturn().getResponse().getContentAsString();

        // 2. GET /api/reconciliation/records -> 200 OK
        String listJson = mockMvc.perform(get("/api/reconciliation/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn().getResponse().getContentAsString();

        String recordId = objectMapper.readTree(listJson).get(0).get("id").asText();

        // 3. GET /api/reconciliation/records/{recordId} -> 200 OK
        mockMvc.perform(get("/api/reconciliation/records/" + recordId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordId))
                .andExpect(jsonPath("$.status").isNotEmpty());

        // 4. POST /api/reconciliation/records/{recordId}/resolve -> 200 OK
        String resolvePayload = """
                {
                  "action": "RESOLVE",
                  "notes": "Reviewed and verified refund request"
                }
                """;
        mockMvc.perform(post("/api/reconciliation/records/" + recordId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }
}
