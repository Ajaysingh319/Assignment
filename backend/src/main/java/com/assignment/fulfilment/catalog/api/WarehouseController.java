package com.assignment.fulfilment.catalog.api;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateWarehouseRequest;
import com.assignment.fulfilment.catalog.dto.WarehouseResponse;
import com.assignment.fulfilment.catalog.dto.UpdateWarehouseRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final CatalogService catalogService;

    public WarehouseController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody CreateWarehouseRequest request) {
        WarehouseResponse created = catalogService.createWarehouse(request);
        return ResponseEntity.created(URI.create("/api/warehouses/" + created.id())).body(created);
    }

    @GetMapping
    public List<WarehouseResponse> list() {
        return catalogService.listWarehouses();
    }

    @GetMapping("/{id}")
    public WarehouseResponse get(@PathVariable UUID id) {
        return catalogService.getWarehouse(id);
    }

    @PutMapping("/{id}")
    public WarehouseResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateWarehouseRequest request) {
        return catalogService.updateWarehouse(id, request);
    }

    @PostMapping("/{id}/deactivate")
    public WarehouseResponse deactivate(@PathVariable UUID id) {
        return catalogService.deactivateWarehouse(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        catalogService.deleteWarehouse(id);
        return ResponseEntity.noContent().build();
    }
}
