package com.smartcart.catalog;

import com.smartcart.catalog.CatalogDtos.*;
import com.smartcart.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Catalog")
public class CatalogController {
    private final ProductService service;
    private final StockEventBroker stockEvents;

    public CatalogController(ProductService service, StockEventBroker stockEvents) {
        this.service = service;
        this.stockEvents = stockEvents;
    }

    @GetMapping(value = "/api/products/stock/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live inventory feed (Server-Sent Events): event 'stock' = [{productId, stock}]")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stockStream() {
        return stockEvents.subscribe();
    }

    @GetMapping("/api/products")
    @Operation(summary = "List products with search, filters, sorting and pagination")
    public PageResponse<ProductDto> list(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) Long categoryId,
                                         @RequestParam(required = false) BigDecimal minPrice,
                                         @RequestParam(required = false) BigDecimal maxPrice,
                                         @RequestParam(defaultValue = "false") boolean inStock,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "12") int size) {
        return service.search(new ProductService.SearchCriteria(q, categoryId, minPrice, maxPrice, inStock,
                false, sort, page, size));
    }

    @GetMapping("/api/products/{id}")
    @Operation(summary = "Product details")
    public ProductDto get(@PathVariable Long id) {
        return service.get(id, false);
    }

    @GetMapping("/api/search")
    @Operation(summary = "Search products by name or description")
    public PageResponse<ProductDto> search(@RequestParam("query") String query,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "12") int size) {
        return service.search(new ProductService.SearchCriteria(query, null, null, null, false, false,
                null, page, size));
    }

    @GetMapping("/api/search/suggest")
    @Operation(summary = "Autocomplete suggestions for the search box")
    public Map<String, List<String>> suggest(@RequestParam("q") String q) {
        return Map.of("suggestions", service.suggest(q));
    }

    @GetMapping("/api/recommendations")
    @Operation(summary = "Recommended products (also-bought, then same category, then top rated)")
    public List<ProductDto> recommendations(@RequestParam(required = false) Long productId,
                                            @RequestParam(defaultValue = "8") int limit) {
        return service.recommendations(productId, limit);
    }

    @GetMapping("/api/categories")
    @Operation(summary = "All categories")
    public List<CategoryDto> categories() {
        return service.categories();
    }

    // ---- Admin (secured in SecurityConfig and here) ----

    @PostMapping("/api/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest req) {
        return service.createCategory(req);
    }

    @PostMapping("/api/products")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: create product")
    public ProductDto create(@Valid @RequestBody ProductRequest req) {
        return service.create(req);
    }

    @PutMapping("/api/products/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: update product")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/api/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: delete (deactivate) product")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/api/admin/products")
    @Operation(summary = "Admin: list all products including inactive ones")
    public PageResponse<ProductDto> adminList(@RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.search(new ProductService.SearchCriteria(q, null, null, null, false, true,
                "newest", page, size));
    }
}
