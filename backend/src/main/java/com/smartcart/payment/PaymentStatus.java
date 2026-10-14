package com.smartcart.payment;

public enum PaymentStatus {
    /** Gateway order created; the shopper has not completed payment yet. */
    CREATED,
    /** Money captured and verified against the gateway API. */
    SUCCEEDED,
    FAILED,
    /** Captured but must be returned (duplicate charge, or order cancelled / unfulfillable). */
    REFUND_PENDING,
    REFUNDED
}
