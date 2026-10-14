package com.smartcart.catalog;

import com.smartcart.catalog.CatalogDtos.*;
import com.smartcart.common.ApiException;
import com.smartcart.common.PageResponse;
import com.smartcart.order.OrderStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductService {
    public static final int MAX_PAGE_SIZE = 60;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final org.springframework.context.ApplicationEventPublisher events;

    public ProductService(ProductRepository products, CategoryRepository categories,
                          org.springframework.context.ApplicationEventPublisher events) {
        this.products = products;
        this.categories = categories;
        this.events = events;
    }

    public record SearchCriteria(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                 boolean inStockOnly, boolean includeInactive, String sort, int page, int size) {}

    @Transactional(readOnly = true)
    public PageResponse<ProductDto> search(SearchCriteria c) {
        int size = Math.min(Math.max(c.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(c.page(), 0);
        Pageable pageable = PageRequest.of(page, size, sortFor(c.sort()));
        return PageResponse.of(products.findAll(spec(c), pageable), ProductDto::of);
    }

    private static Sort sortFor(String sort) {
        if (sort == null) {
            return Sort.by(Sort.Order.desc("ratingCount"), Sort.Order.asc("id"));
        }
        return switch (sort) {
            case "price_asc" -> Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"));
            case "price_desc" -> Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id"));
            case "rating" -> Sort.by(Sort.Order.desc("ratingAvg"), Sort.Order.desc("ratingCount"), Sort.Order.asc("id"));
            case "newest" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            default -> Sort.by(Sort.Order.desc("ratingCount"), Sort.Order.asc("id"));
        };
    }

    private static Specification<Product> spec(SearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (!c.includeInactive()) {
                ps.add(cb.isTrue(root.get("active")));
            }
            if (c.q() != null && !c.q().isBlank()) {
                String like = "%" + escapeLike(c.q().trim().toLowerCase(Locale.ROOT)) + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, '!'),
                        cb.like(cb.lower(root.get("description")), like, '!')));
            }
            if (c.categoryId() != null) {
                ps.add(cb.equal(root.get("category").get("id"), c.categoryId()));
            }
            if (c.minPrice() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("price"), c.minPrice()));
            }
            if (c.maxPrice() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("price"), c.maxPrice()));
            }
            if (c.inStockOnly()) {
                ps.add(cb.greaterThan(root.get("stock"), 0));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    /** Escapes LIKE wildcards using '!' as the escape character (portable across HQL and SQL dialects). */
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    @Transactional(readOnly = true)
    public ProductDto get(Long id, boolean allowInactive) {
        Product p = products.findWithCategoryById(id).orElseThrow(() -> ApiException.notFound("Product"));
        if (!p.isActive() && !allowInactive) {
            throw ApiException.notFound("Product");
        }
        return ProductDto.of(p);
    }

    @Transactional(readOnly = true)
    public List<String> suggest(String q) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        return products.suggestNames(escapeLike(q.trim()), PageRequest.of(0, 8));
    }

    @Transactional(readOnly = true)
    public List<ProductDto> recommendations(Long productId, int limit) {
        int n = Math.min(Math.max(limit, 1), 12);
        if (productId == null) {
            return products.findTopRated(PageRequest.of(0, n)).stream().map(ProductDto::of).toList();
        }
        Product base = products.findWithCategoryById(productId).orElseThrow(() -> ApiException.notFound("Product"));

        LinkedHashMap<Long, Product> picked = new LinkedHashMap<>();
        List<Long> alsoBoughtIds = products.findAlsoBoughtIds(productId,
                EnumSet.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED), PageRequest.of(0, n));
        if (!alsoBoughtIds.isEmpty()) {
            Map<Long, Product> byId = products.findAllById(alsoBoughtIds).stream()
                    .filter(Product::isActive)
                    .collect(Collectors.toMap(Product::getId, Function.identity()));
            alsoBoughtIds.stream().map(byId::get).filter(Objects::nonNull).forEach(p -> picked.put(p.getId(), p));
        }
        if (picked.size() < n && base.getCategory() != null) {
            products.findSimilar(base.getCategory().getId(), productId, PageRequest.of(0, n))
                    .forEach(p -> picked.putIfAbsent(p.getId(), p));
        }
        if (picked.size() < n) {
            products.findTopRated(PageRequest.of(0, n + 1)).stream()
                    .filter(p -> !p.getId().equals(productId))
                    .forEach(p -> picked.putIfAbsent(p.getId(), p));
        }
        return picked.values().stream().limit(n).map(ProductDto::of).toList();
    }

    @Transactional
    public ProductDto create(ProductRequest req) {
        Product p = new Product();
        apply(p, req);
        return ProductDto.of(products.save(p));
    }

    @Transactional
    public ProductDto update(Long id, ProductRequest req) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        int before = p.getStock();
        boolean wasActive = p.isActive();
        apply(p, req);
        if (before != p.getStock() || wasActive != p.isActive()) {
            events.publishEvent(StockChangedEvent.of(List.of(p)));
        }
        return ProductDto.of(p);
    }

    /** Soft delete: past orders keep referencing the product, so it is hidden rather than removed. */
    @Transactional
    public void delete(Long id) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        p.setActive(false);
    }

    private void apply(Product p, ProductRequest req) {
        if (req.mrp() != null && req.mrp().compareTo(req.price()) < 0) {
            throw ApiException.badRequest("MRP cannot be lower than the selling price");
        }
        p.setName(req.name().trim());
        p.setDescription(req.description());
        p.setPrice(req.price());
        p.setMrp(req.mrp());
        p.setStock(req.stock());
        p.setImageUrl(req.imageUrl() == null || req.imageUrl().isBlank() ? null : req.imageUrl());
        p.setImages(req.images());
        p.setAttributes(req.attributes());
        if (req.active() != null) {
            p.setActive(req.active());
        }
        p.setCategory(req.categoryId() == null ? null
                : categories.findById(req.categoryId()).orElseThrow(() -> ApiException.badRequest("Unknown category")));
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        return categories.findAll(Sort.by("name")).stream().map(CategoryDto::of).toList();
    }

    @Transactional
    public CategoryDto createCategory(CategoryRequest req) {
        String name = req.name().trim();
        if (categories.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("Category already exists");
        }
        return CategoryDto.of(categories.save(new Category(name, slugify(name))));
    }

    public static String slugify(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
