package com.smartcart.payment;

import com.smartcart.auth.AuthUser;
import com.smartcart.order.OrderDtos.CheckoutRequest;
import com.smartcart.payment.PaymentDtos.*;
import com.smartcart.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "Payments")
public class PaymentController {
    private final PaymentService payments;
    private final UserRepository users;

    public PaymentController(PaymentService payments, UserRepository users) {
        this.payments = payments;
        this.users = users;
    }

    @GetMapping("/api/payments/config")
    @Operation(summary = "Which gateways are configured and in which mode (live / test / sandbox / production)")
    public PaymentConfig config() {
        return payments.config();
    }

    @PostMapping("/api/checkout")
    @Operation(summary = "Place an order from the cart and open a real Razorpay (or PhonePe) payment. "
            + "Send an Idempotency-Key header so retries of the same request never create a second order.")
    public CheckoutResponse checkout(@AuthenticationPrincipal AuthUser user,
                                     @Parameter(description = "Unique per checkout attempt, 8-64 chars (e.g. a UUID)")
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @Valid @RequestBody CheckoutRequest req) {
        return payments.checkout(user.id(), displayName(user), user.email(), req, idempotencyKey);
    }

    @PostMapping("/api/orders/{orderId}/pay")
    @Operation(summary = "Pay (or retry paying) an unpaid order. Reuses the open Razorpay order - never creates a duplicate.")
    public CheckoutResponse retry(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId,
                                  @RequestBody(required = false) RetryRequest req) {
        return payments.retry(user.id(), displayName(user), user.email(), orderId, req == null ? null : req.gateway());
    }

    @PostMapping("/api/payments/razorpay/verify")
    @Operation(summary = "Verify the Razorpay Checkout signature, fetch the payment from Razorpay, capture it and mark the order paid")
    public PaymentResult verifyRazorpay(@AuthenticationPrincipal AuthUser user,
                                        @Valid @RequestBody RazorpayVerifyRequest req) {
        return payments.confirmRazorpay(user.id(), req);
    }

    @PostMapping("/api/payments/phonepe/sync/{orderId}")
    @Operation(summary = "Fetch the PhonePe order status after the shopper returns from PhonePe")
    public PaymentResult syncPhonePe(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId) {
        return payments.syncPhonePe(user.id(), orderId);
    }

    // ---- Webhooks: authenticated by gateway signatures, not JWT ----

    @PostMapping(value = "/api/webhooks/razorpay", consumes = "application/json")
    @Operation(summary = "Razorpay webhook (payment.authorized, payment.captured, order.paid, payment.failed)")
    public ResponseEntity<Map<String, String>> razorpayWebhook(@RequestBody String rawBody, HttpServletRequest req) {
        payments.handleRazorpayWebhook(rawBody, req.getHeader("X-Razorpay-Signature"),
                req.getHeader("X-Razorpay-Event-Id"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping(value = "/api/webhooks/phonepe", consumes = "application/json")
    @Operation(summary = "PhonePe webhook (checkout.order.completed / checkout.order.failed)")
    public ResponseEntity<Map<String, String>> phonePeWebhook(@RequestBody String rawBody, HttpServletRequest req) {
        payments.handlePhonePeWebhook(rawBody, req.getHeader("Authorization"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private String displayName(AuthUser user) {
        return users.findById(user.id()).map(u -> u.getName()).orElse("");
    }
}
