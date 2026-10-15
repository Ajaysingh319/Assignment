package com.assignment.fulfilment.inventory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assignment.fulfilment.common.web.CorrelationIdFilter;
import com.assignment.fulfilment.common.web.GlobalExceptionHandler;
import com.assignment.fulfilment.inventory.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InventoryController.class)
@Import({CorrelationIdFilter.class, GlobalExceptionHandler.class})
class InventoryValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryService inventoryService;

    @Test
    void adjustmentRequiresIdsAndDelta() throws Exception {
        mockMvc.perform(post("/api/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
