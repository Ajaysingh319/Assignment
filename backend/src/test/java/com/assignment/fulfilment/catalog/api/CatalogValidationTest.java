package com.assignment.fulfilment.catalog.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.common.web.CorrelationIdFilter;
import com.assignment.fulfilment.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ProductController.class, WarehouseController.class})
@Import({CorrelationIdFilter.class, GlobalExceptionHandler.class})
class CatalogValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService catalogService;

    @Test
    void createProductRejectsBlankSkuAndName() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void createProductRejectsExceededLength() throws Exception {
        String longSku = "A".repeat(65);
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + longSku + "\",\"name\":\"Valid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("sku: must be at most 64 characters")));
    }

    @Test
    void createWarehouseRejectsBlankCode() throws Exception {
        mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"East\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createWarehouseRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-1\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
