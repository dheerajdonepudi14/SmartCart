package com.smartcart.review;

import com.smartcart.auth.AuthUser;
import com.smartcart.catalog.Product;
import com.smartcart.catalog.ProductRepository;
import com.smartcart.common.ApiException;
import com.smartcart.common.PageResponse;
import com.smartcart.order.OrderRepository;
import com.smartcart.order.OrderStatus;
import com.smartcart.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Reviews")
public class ReviewController {

    public record ReviewRequest(@NotNull Long productId, @NotNull @Min(1) @Max(5) Integer rating,
                                @Size(max = 120) String title, @Size(max = 4000) String comment) {}

    public record ReviewDto(Long id, Long productId, String userName, int rating, String title, String comment,
                            boolean verifiedPurchase, Instant createdAt) {
        static ReviewDto of(Review r) {
            String name = r.getUser().getName();
            // Show "Priya S." style names publicly, never emails.
            String[] parts = name.trim().split("\\s+");
            String display = parts.length > 1 ? parts[0] + " " + parts[parts.length - 1].charAt(0) + "." : parts[0];
            return new ReviewDto(r.getId(), r.getProduct().getId(), display, r.getRating(), r.getTitle(),
                    r.getComment(), r.isVerifiedPurchase(), r.getCreatedAt());
        }
    }

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Reviews for a product, newest first")
    public PageResponse<ReviewDto> list(@RequestParam Long productId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return service.list(productId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a review (one per product per user)")
    public ReviewDto create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ReviewRequest req) {
        return service.create(user.id(), req);
    }

    @Service
    public static class ReviewService {
        private final ReviewRepository reviews;
        private final ProductRepository products;
        private final UserRepository users;
        private final OrderRepository orders;

        public ReviewService(ReviewRepository reviews, ProductRepository products, UserRepository users,
                             OrderRepository orders) {
            this.reviews = reviews;
            this.products = products;
            this.users = users;
            this.orders = orders;
        }

        @Transactional(readOnly = true)
        public PageResponse<ReviewDto> list(Long productId, int page, int size) {
            return PageResponse.of(reviews.findByProductIdOrderByCreatedAtDesc(productId,
                    PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50))), ReviewDto::of);
        }

        @Transactional
        public ReviewDto create(Long userId, ReviewRequest req) {
            if (reviews.existsByUserIdAndProductId(userId, req.productId())) {
                throw ApiException.conflict("You have already reviewed this product");
            }
            List<Product> locked = products.lockAllByIdIn(List.of(req.productId()));
            Product product = locked.stream().findFirst().filter(Product::isActive)
                    .orElseThrow(() -> ApiException.notFound("Product"));
            boolean verified = orders.hasPurchased(userId, req.productId(), OrderStatus.purchased());
            Review r = reviews.save(new Review(users.getReferenceById(userId), product, req.rating(),
                    blankToNull(req.title()), blankToNull(req.comment()), verified));
            product.addRating(req.rating());
            return ReviewDto.of(reviews.findById(r.getId()).orElse(r));
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }
}
