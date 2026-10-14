package com.smartcart.order;

import com.smartcart.auth.AuthUser;
import com.smartcart.common.PageResponse;
import com.smartcart.order.OrderDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Tag(name = "Orders")
public class OrderController {
    private final OrderService orders;
    private final OrderEventBroker broker;

    public OrderController(OrderService orders, OrderEventBroker broker) {
        this.orders = orders;
        this.broker = broker;
    }

    @GetMapping("/api/orders")
    @Operation(summary = "Current user's order history")
    public PageResponse<OrderSummaryDto> list(@AuthenticationPrincipal AuthUser user,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        return orders.listForUser(user.id(), page, size);
    }

    @GetMapping("/api/orders/{id}")
    @Operation(summary = "Order details (owner or admin)")
    public OrderDetailDto get(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return orders.getForUser(user.id(), id, user.isAdmin());
    }

    @PostMapping("/api/orders/{id}/cancel")
    @Operation(summary = "Cancel an unpaid order and release its stock")
    public OrderDetailDto cancel(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return orders.cancelByUser(user.id(), id);
    }

    @GetMapping(value = "/api/orders/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live order status stream (Server-Sent Events)")
    public SseEmitter events(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        OrderDetailDto current = orders.getForUser(user.id(), id, user.isAdmin());
        return broker.subscribeOrder(id, current);
    }

    // ---- Admin ----

    @GetMapping("/api/admin/orders")
    @Operation(summary = "Admin: all orders, optionally filtered by status")
    public PageResponse<OrderSummaryDto> adminList(@RequestParam(required = false) OrderStatus status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return orders.listAll(status, page, size);
    }

    @PutMapping("/api/admin/orders/{id}/status")
    @Operation(summary = "Admin: move an order to SHIPPED / DELIVERED / CANCELLED")
    public OrderDetailDto adminUpdate(@PathVariable Long id, @Valid @RequestBody StatusUpdateRequest req) {
        return orders.adminUpdateStatus(id, req.status());
    }

    @GetMapping(value = "/api/admin/orders/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Admin: live feed of order status changes (Server-Sent Events)")
    public SseEmitter adminEvents() {
        return broker.subscribeAdmin();
    }
}
