package com.smartcart.catalog;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class CatalogDtos {
    private CatalogDtos() {}

    public record CategoryDto(Long id, String name, String slug) {
        public static CategoryDto of(Category c) {
            return new CategoryDto(c.getId(), c.getName(), c.getSlug());
        }
    }

    public record ProductDto(Long id, String name, String description, BigDecimal price, BigDecimal mrp,
                             int stock, Long categoryId, String categoryName, String imageUrl,
                             List<String> images, Map<String, String> attributes,
                             BigDecimal ratingAvg, int ratingCount, boolean active) {
        public static ProductDto of(Product p) {
            Category c = p.getCategory();
            return new ProductDto(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getMrp(),
                    p.getStock(), c == null ? null : c.getId(), c == null ? null : c.getName(),
                    p.getImageUrl(), new java.util.ArrayList<>(p.getImages()), new java.util.LinkedHashMap<>(p.getAttributes()),
                    p.getRatingAvg(), p.getRatingCount(), p.isActive());
        }
    }

    public record ProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 5000) String description,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal mrp,
            @NotNull @Min(0) @Max(1_000_000) Integer stock,
            Long categoryId,
            @Size(max = 500) @Pattern(regexp = "^(https://.*)?$", message = "must be an https URL") String imageUrl,
            @Size(max = 10) List<@Size(max = 500) @Pattern(regexp = "^https://.*$", message = "must be an https URL") String> images,
            @Size(max = 30) Map<@Size(max = 50) String, @Size(max = 200) String> attributes,
            Boolean active) {}

    public record CategoryRequest(@NotBlank @Size(max = 100) String name) {}
}
