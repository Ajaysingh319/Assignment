package com.assignment.fulfilment.catalog.api;

import com.assignment.fulfilment.catalog.CatalogService;
import com.assignment.fulfilment.catalog.dto.CreateProductRequest;
import com.assignment.fulfilment.catalog.dto.ProductResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final CatalogService catalogService;

    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = catalogService.createProduct(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @GetMapping
    public List<ProductResponse> list() {
        return catalogService.listProducts();
    }
}
