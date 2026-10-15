package com.assignment.fulfilment.catalog;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogApiTest {

    @Autowired
    private MockMvc mockMvc;

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
}
