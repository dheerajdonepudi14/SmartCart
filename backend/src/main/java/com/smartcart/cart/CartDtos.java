package com.smartcart.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class CartDtos {
    private CartDtos() {}

    public static final int MAX_QTY_PER_ITEM = 10;

    /** Sets the quantity of one product in the cart. Quantity 0 removes the line. */
    public record CartUpdateRequest(@NotNull Long productId, @NotNull @Min(0) @Max(MAX_QTY_PER_ITEM) Integer quantity) {}

    public record CartMergeRequest(@NotNull @Size(max = 50) List<@Valid CartUpdateRequest> items) {}

    public record CartLine(Long productId, String name, String imageUrl, BigDecimal price, int quantity,
                           int stock, boolean available, BigDecimal lineTotal) {}

    public record CartDto(List<CartLine> items, int itemCount, BigDecimal subtotal, BigDecimal shippingFee,
                          BigDecimal total) {}
}
