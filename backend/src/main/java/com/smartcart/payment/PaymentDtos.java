package com.smartcart.payment;

import com.smartcart.order.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public final class PaymentDtos {
    private PaymentDtos() {}

    public record Prefill(String name, String email, String contact) {}

    /** Everything the browser needs to open Razorpay checkout.js. The key id is public by design. */
    public record RazorpayOptions(String keyId, String razorpayOrderId, long amount, String currency, String name,
                                  String description, Prefill prefill) {}

    /**
     * @param payUrl       storefront page that opens the payment for this order (handy from Postman)
     * @param replayed     true when an Idempotency-Key matched an earlier checkout and no new order was created
     */
    public record CheckoutResponse(Long orderId, OrderStatus orderStatus, PaymentGatewayType gateway,
                                   long amountPaise, String currency, RazorpayOptions razorpay,
                                   String redirectUrl, String payUrl, boolean replayed) {}

    public record RetryRequest(PaymentGatewayType gateway) {}

    public record RazorpayVerifyRequest(@NotNull Long orderId,
                                        @NotBlank @Size(max = 100) String razorpayOrderId,
                                        @NotBlank @Size(max = 100) String razorpayPaymentId,
                                        @NotBlank @Size(max = 200) String razorpaySignature) {}

    public record PaymentResult(Long orderId, OrderStatus orderStatus, PaymentStatus paymentStatus,
                                String gatewayPaymentId, String message) {}

    /** mode: live | test | sandbox | production | not_configured */
    public record GatewayInfo(boolean available, String mode) {}

    public record PaymentConfig(Map<PaymentGatewayType, GatewayInfo> gateways, String razorpayKeyId) {}
}
