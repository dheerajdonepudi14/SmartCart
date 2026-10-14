package com.smartcart.cart;

import com.smartcart.cart.CartDtos.*;
import com.smartcart.catalog.Product;
import com.smartcart.catalog.ProductRepository;
import com.smartcart.common.ApiException;
import com.smartcart.config.AppProperties;
import com.smartcart.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartService {
    private final CartItemRepository cart;
    private final ProductRepository products;
    private final UserRepository users;
    private final AppProperties.Shipping shipping;

    public CartService(CartItemRepository cart, ProductRepository products, UserRepository users, AppProperties props) {
        this.cart = cart;
        this.products = products;
        this.users = users;
        this.shipping = props.shipping();
    }

    @Transactional(readOnly = true)
    public CartDto get(Long userId) {
        return toDto(cart.findByUserIdOrderByIdAsc(userId));
    }

    @Transactional
    public CartDto setQuantity(Long userId, CartUpdateRequest req) {
        apply(userId, req.productId(), req.quantity(), false);
        return get(userId);
    }

    /** Merges a guest (browser-side) cart into the account cart after sign-in. */
    @Transactional
    public CartDto merge(Long userId, CartMergeRequest req) {
        for (CartUpdateRequest item : req.items()) {
            try {
                apply(userId, item.productId(), item.quantity(), true);
            } catch (ApiException ignored) {
                // Skip products that vanished or went out of stock since they were added.
            }
        }
        return get(userId);
    }

    @Transactional
    public CartDto clear(Long userId) {
        cart.deleteAllForUser(userId);
        return get(userId);
    }

    private void apply(Long userId, Long productId, int quantity, boolean additive) {
        var existing = cart.findByUserIdAndProductId(userId, productId);
        if (quantity <= 0) {
            existing.ifPresent(cart::delete);
            return;
        }
        Product p = products.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> ApiException.notFound("Product"));
        int target = additive ? quantity + existing.map(CartItem::getQuantity).orElse(0) : quantity;
        target = Math.min(target, CartDtos.MAX_QTY_PER_ITEM);
        if (p.getStock() < target) {
            throw ApiException.conflict("Only " + p.getStock() + " left in stock for " + p.getName());
        }
        if (existing.isPresent()) {
            existing.get().setQuantity(target);
        } else {
            cart.save(new CartItem(users.getReferenceById(userId), p, target));
        }
    }

    public BigDecimal shippingFor(BigDecimal subtotal) {
        if (subtotal.signum() == 0 || subtotal.compareTo(shipping.freeAbove()) >= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return shipping.fee().setScale(2);
    }

    private CartDto toDto(List<CartItem> items) {
        List<CartLine> lines = items.stream().map(ci -> {
            Product p = ci.getProduct();
            boolean available = p.isActive() && p.getStock() >= ci.getQuantity();
            return new CartLine(p.getId(), p.getName(), p.getImageUrl(), p.getPrice(), ci.getQuantity(),
                    p.getStock(), available, p.getPrice().multiply(BigDecimal.valueOf(ci.getQuantity())));
        }).toList();
        BigDecimal subtotal = lines.stream().map(CartLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        BigDecimal fee = shippingFor(subtotal);
        int count = lines.stream().mapToInt(CartLine::quantity).sum();
        return new CartDto(lines, count, subtotal, fee, subtotal.add(fee));
    }
}
