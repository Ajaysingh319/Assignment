package com.assignment.fulfilment.catalog;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createAndListProductUsesUtcTimestamps() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-1","name":"Widget","description":"A widget","active":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.createdAt", endsWith("Z")))
                .andExpect(jsonPath("$.updatedAt", endsWith("Z")));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("SKU-1"));
    }

    @Test
    void duplicateSkuReturnsConflict() throws Exception {
        String body = """
                {"sku":"SKU-DUP","name":"First"}
                """;
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void duplicateWarehouseCodeReturnsConflict() throws Exception {
        String body = """
                {"code":"WH-DUP","name":"East"}
                """;
        mockMvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void productCrudAndDeactivation() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-CRUD-1","name":"Original Name","description":"Original Desc","active":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        String productId = json.get("id").asText();

        // Get by ID
        mockMvc.perform(get("/api/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Original Name"));

        // Update
        mockMvc.perform(put("/api/products/" + productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated Name","description":"Updated Desc","active":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.description").value("Updated Desc"));

        // Deactivate
        mockMvc.perform(post("/api/products/" + productId + "/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Delete unreferenced product succeeds
        mockMvc.perform(delete("/api/products/" + productId))
                .andExpect(status().isNoContent());

        // Get after delete returns 404
        mockMvc.perform(get("/api/products/" + productId))
                .andExpect(status().isNotFound());
    }

    @Test
    void warehouseCrudAndDeactivation() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"WH-CRUD-1","name":"South WH","location":"Atlanta","active":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        String warehouseId = json.get("id").asText();

        // Get by ID
        mockMvc.perform(get("/api/warehouses/" + warehouseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WH-CRUD-1"));

        // Update
        mockMvc.perform(put("/api/warehouses/" + warehouseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated South WH","location":"Savannah","active":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated South WH"));

        // Deactivate
        mockMvc.perform(post("/api/warehouses/" + warehouseId + "/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Delete unreferenced warehouse succeeds
        mockMvc.perform(delete("/api/warehouses/" + warehouseId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteProductOrWarehouseRejectedWhenReferencedByInventory() throws Exception {
        MvcResult prodRes = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-REF-1","name":"Ref Product"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult whRes = mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"WH-REF-1","name":"Ref Warehouse"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String productId = objectMapper.readTree(prodRes.getResponse().getContentAsString()).get("id").asText();
        String warehouseId = objectMapper.readTree(whRes.getResponse().getContentAsString()).get("id").asText();

        // Initialize inventory
        mockMvc.perform(post("/api/inventory/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","warehouseId":"%s","initialStock":10}
                                """.formatted(productId, warehouseId)))
                .andExpect(status().isOk());

        // Deleting referenced product fails with 409 Conflict
        mockMvc.perform(delete("/api/products/" + productId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("referenced by existing inventory")));

        // Deleting referenced warehouse fails with 409 Conflict
        mockMvc.perform(delete("/api/warehouses/" + warehouseId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("referenced by existing inventory")));
    }
}
