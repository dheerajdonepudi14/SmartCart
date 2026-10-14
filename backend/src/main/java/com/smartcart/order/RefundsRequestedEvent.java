package com.smartcart.order;

/** Signals that an order has payments marked REFUND_PENDING which should be refunded now. */
public record RefundsRequestedEvent(Long orderId) {}
