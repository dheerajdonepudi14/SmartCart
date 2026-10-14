package com.smartcart.cart;

import com.smartcart.auth.AuthUser;
import com.smartcart.cart.CartDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@Tag(name = "Cart")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Current user's cart")
    public CartDto get(@AuthenticationPrincipal AuthUser user) {
        return service.get(user.id());
    }

    @PostMapping
    @Operation(summary = "Set quantity for a product in the cart (0 removes it)")
    public CartDto update(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CartUpdateRequest req) {
        return service.setQuantity(user.id(), req);
    }

    @PostMapping("/merge")
    @Operation(summary = "Merge a guest cart into the account cart")
    public CartDto merge(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CartMergeRequest req) {
        return service.merge(user.id(), req);
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart")
    public CartDto clear(@AuthenticationPrincipal AuthUser user) {
        return service.clear(user.id());
    }
}
