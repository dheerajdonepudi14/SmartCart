package com.smartcart.order;

import java.time.Instant;

/** Published inside a transaction; delivered to live listeners only after commit. */
public record OrderStatusChangedEvent(Long orderId, Long userId, OrderStatus status, String paymentStatus,
                                      String message, Instant at) {}
