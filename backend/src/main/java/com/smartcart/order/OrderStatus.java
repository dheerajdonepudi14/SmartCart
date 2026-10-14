package com.smartcart.order;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    /** Stock is reserved; waiting for a successful payment (the user may retry failed attempts). */
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    /** Cancelled by the user, by an admin, or automatically when an unpaid order expires. */
    CANCELLED;

    public Set<OrderStatus> next() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(PAID, CANCELLED);
            case PAID -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canMoveTo(OrderStatus target) {
        return next().contains(target);
    }

    public static Set<OrderStatus> purchased() {
        return EnumSet.of(PAID, SHIPPED, DELIVERED);
    }
}
