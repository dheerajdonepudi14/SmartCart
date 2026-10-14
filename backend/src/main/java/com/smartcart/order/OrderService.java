package com.smartcart.order;

import com.smartcart.cart.CartItemRepository;
import com.smartcart.cart.CartService;
import com.smartcart.catalog.Product;
import com.smartcart.catalog.ProductRepository;
import com.smartcart.catalog.StockChangedEvent;
import com.smartcart.common.ApiException;
import com.smartcart.common.PageResponse;
import com.smartcart.order.OrderDtos.*;
import com.smartcart.payment.Payment;
import com.smartcart.payment.PaymentRepository;
import com.smartcart.payment.PaymentStatus;
import com.smartcart.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final CartItemRepository cart;
    private final CartService cartService;
    private final ProductRepository products;
    private final UserRepository users;
    private final PaymentRepository payments;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, CartItemRepository cart, CartService cartService,
                        ProductRepository products, UserRepository users, PaymentRepository payments,
                        ApplicationEventPublisher events) {
        this.orders = orders;
        this.cart = cart;
        this.cartService = cartService;
        this.products = products;
        this.users = users;
        this.payments = payments;
        this.events = events;
    }

    /**
     * Turns the user's cart into a PENDING_PAYMENT order and reserves stock under row locks.
     * The cart itself is only cleared once payment succeeds, so abandoned payments lose nothing.
     */
    @Transactional
    public Order createPendingOrder(Long userId, CheckoutRequest req, String idempotencyKey) {
        var lines = cart.findQuantities(userId);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Your cart is empty");
        }
        Map<Long, Integer> qtyByProduct = lines.stream()
                .collect(Collectors.toMap(CartItemRepository.CartQuantity::getProductId,
                        CartItemRepository.CartQuantity::getQuantity, Integer::sum, LinkedHashMap::new));
        Map<Long, Product> locked = products.lockAllByIdIn(qtyByProduct.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Order order = new Order(users.getReferenceById(userId), req.gateway(), req.address().toEntity(), idempotencyKey);
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var e : qtyByProduct.entrySet()) {
            Product p = locked.get(e.getKey());
            int qty = e.getValue();
            if (p == null || !p.isActive()) {
                throw ApiException.conflict("A product in your cart is no longer available");
            }
            if (p.getStock() < qty) {
                throw ApiException.conflict("Only " + p.getStock() + " left in stock for " + p.getName());
            }
            p.setStock(p.getStock() - qty);
            OrderItem item = new OrderItem(p, qty);
            order.addItem(item);
            subtotal = subtotal.add(item.lineTotal());
        }
        subtotal = subtotal.setScale(2);
        order.setTotals(subtotal, cartService.shippingFor(subtotal));
        if (order.totalPaise() < 100) {
            throw ApiException.badRequest("Order total must be at least ₹1");
        }
        Order saved = orders.saveAndFlush(order);
        events.publishEvent(StockChangedEvent.of(List.copyOf(locked.values())));
        log.info("Order created orderId={} userId={} total={}", saved.getId(), userId, saved.getTotalAmount());
        publish(saved, null, "Order placed, awaiting payment");
        return saved;
    }

    /**
     * Idempotently moves an order to PAID. Called from payment confirmation paths, which hold a
     * lock on the payment row. Returns false when a late payment could not be honoured.
     */
    @Transactional
    public boolean applyPaymentSuccess(Order order) {
        switch (order.getStatus()) {
            case PAID, SHIPPED, DELIVERED -> {
                return true;
            }
            case CANCELLED -> {
                // Payment landed after the order expired: re-reserve stock if we still can.
                if (!reserveAgain(order)) {
                    log.error("Late payment for cancelled order {} could not be fulfilled; refund required", order.getId());
                    return false;
                }
                log.warn("Late payment revived cancelled order {}", order.getId());
            }
            case PENDING_PAYMENT -> { }
        }
        order.setStatus(OrderStatus.PAID);
        Set<Long> productIds = order.getItems().stream().map(i -> i.getProduct().getId()).collect(Collectors.toSet());
        cart.deleteByUserIdAndProductIdIn(order.getUser().getId(), productIds);
        log.info("Order paid orderId={}", order.getId());
        publish(order, "SUCCEEDED", "Payment received");
        return true;
    }

    /** A failed attempt leaves the order payable; the user can retry until it expires. */
    @Transactional
    public void notifyPaymentFailed(Order order, String reason) {
        publish(order, "FAILED", reason == null ? "Payment failed, please try again" : reason);
    }

    private boolean reserveAgain(Order order) {
        Map<Long, Integer> qty = order.getItems().stream()
                .collect(Collectors.toMap(i -> i.getProduct().getId(), OrderItem::getQuantity, Integer::sum));
        List<Product> locked = products.lockAllByIdIn(qty.keySet());
        if (locked.size() != qty.size() || locked.stream().anyMatch(p -> p.getStock() < qty.get(p.getId()))) {
            return false;
        }
        locked.forEach(p -> p.setStock(p.getStock() - qty.get(p.getId())));
        events.publishEvent(StockChangedEvent.of(locked));
        return true;
    }

    private void releaseStock(Order order) {
        Map<Long, Integer> qty = order.getItems().stream()
                .collect(Collectors.toMap(i -> i.getProduct().getId(), OrderItem::getQuantity, Integer::sum));
        List<Product> locked = products.lockAllByIdIn(qty.keySet());
        locked.forEach(p -> p.setStock(p.getStock() + qty.get(p.getId())));
        events.publishEvent(StockChangedEvent.of(locked));
    }

    @Transactional
    public OrderDetailDto cancelByUser(Long userId, Long orderId) {
        Order o = owned(userId, orderId);
        if (o.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("Only unpaid orders can be cancelled here; contact support for paid orders");
        }
        cancel(o, "Cancelled by you");
        return detail(o);
    }

    /** Releases stock held by orders that were never paid within the payment window. */
    @Transactional
    public int expireUnpaid(Instant cutoff) {
        List<Order> stale = orders.findByStatusAndCreatedAtBefore(OrderStatus.PENDING_PAYMENT, cutoff);
        stale.forEach(o -> cancel(o, "Payment window expired"));
        if (!stale.isEmpty()) {
            log.info("Expired {} unpaid orders", stale.size());
        }
        return stale.size();
    }

    private void cancel(Order o, String reason) {
        boolean holdsStock = o.getStatus() == OrderStatus.PENDING_PAYMENT || o.getStatus() == OrderStatus.PAID;
        o.setStatus(OrderStatus.CANCELLED);
        if (holdsStock) {
            releaseStock(o);
        }
        publish(o, null, reason);
    }

    @Transactional
    public OrderDetailDto adminUpdateStatus(Long orderId, OrderStatus target) {
        Order o = orders.findDetailedById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!o.getStatus().canMoveTo(target)) {
            throw ApiException.conflict("Cannot move order from " + o.getStatus() + " to " + target);
        }
        if (target == OrderStatus.PAID) {
            throw ApiException.badRequest("Orders become PAID only through a verified payment");
        }
        if (target == OrderStatus.CANCELLED) {
            boolean wasPaid = o.getStatus() == OrderStatus.PAID;
            if (wasPaid) {
                // The refund itself is sent to the gateway after commit (PaymentService#onRefundsRequested).
                payments.findByOrderIdAndStatus(o.getId(), PaymentStatus.SUCCEEDED)
                        .forEach(p -> p.requireRefund(null, null, "Order cancelled by store"));
                events.publishEvent(new RefundsRequestedEvent(o.getId()));
            }
            cancel(o, wasPaid ? "Cancelled by store; refund will be issued" : "Cancelled by store");
        } else {
            o.setStatus(target);
            publish(o, null, target == OrderStatus.SHIPPED ? "Your order has shipped" : "Your order was delivered");
        }
        return detail(o);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryDto> listForUser(Long userId, int page, int size) {
        Page<Order> p = orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 50)));
        return PageResponse.of(p, this::summary);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryDto> listAll(OrderStatus status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<Order> p = status == null ? orders.findAllByOrderByCreatedAtDesc(pr)
                : orders.findByStatusOrderByCreatedAtDesc(status, pr);
        return PageResponse.of(p, this::summary);
    }

    @Transactional(readOnly = true)
    public OrderDetailDto getForUser(Long userId, Long orderId, boolean admin) {
        Order o = admin ? orders.findDetailedById(orderId).orElseThrow(() -> ApiException.notFound("Order"))
                : owned(userId, orderId);
        return detail(o);
    }

    @Transactional(readOnly = true)
    public Order owned(Long userId, Long orderId) {
        Order o = orders.findDetailedById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!o.getUser().getId().equals(userId)) {
            // Same response as a missing order so ids can't be probed.
            throw ApiException.notFound("Order");
        }
        return o;
    }

    public OrderDetailDto detail(Order o) {
        Payment latest = payments.findFirstByOrderIdOrderByIdDesc(o.getId()).orElse(null);
        return new OrderDetailDto(o.getId(), o.getStatus(), o.getSubtotal(), o.getShippingFee(), o.getTotalAmount(),
                o.getCurrency(), o.getPaymentGateway(), AddressDto.of(o.getAddress()),
                o.getItems().stream().map(OrderItemDto::of).toList(), PaymentDto.of(latest),
                o.getUser().getEmail(), o.getCreatedAt(), o.getUpdatedAt());
    }

    private OrderSummaryDto summary(Order o) {
        List<OrderItem> items = o.getItems();
        OrderItem first = items.isEmpty() ? null : items.get(0);
        int count = items.stream().mapToInt(OrderItem::getQuantity).sum();
        return new OrderSummaryDto(o.getId(), o.getStatus(), o.getTotalAmount(), o.getCurrency(), count,
                first == null ? null : first.getProductName(), first == null ? null : first.getImageUrl(),
                o.getPaymentGateway(), o.getCreatedAt());
    }

    private void publish(Order o, String paymentStatus, String message) {
        events.publishEvent(new OrderStatusChangedEvent(o.getId(), o.getUser().getId(), o.getStatus(),
                paymentStatus, message, Instant.now()));
    }
}
