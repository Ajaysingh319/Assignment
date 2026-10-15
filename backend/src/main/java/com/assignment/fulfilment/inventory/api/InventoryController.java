package com.assignment.fulfilment.inventory.api;

import com.assignment.fulfilment.inventory.InventoryService;
import com.assignment.fulfilment.inventory.dto.AdjustInventoryRequest;
import com.assignment.fulfilment.inventory.dto.InventoryListResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public InventoryListResponse list() {
        return inventoryService.listGrouped();
    }

    @PostMapping("/adjustments")
    public InventoryListResponse adjust(@Valid @RequestBody AdjustInventoryRequest request) {
        return inventoryService.adjust(request);
    }
}
