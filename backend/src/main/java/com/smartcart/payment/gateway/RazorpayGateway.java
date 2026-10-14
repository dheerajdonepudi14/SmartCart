package com.smartcart.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartcart.common.Crypto;
import com.smartcart.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Razorpay REST API (https://razorpay.com/docs/api/) over HTTPS with Basic auth (key_id:key_secret).
 *
 * <p>Flow: server creates an Order -> browser opens Checkout (checkout.js) with that order id ->
 * browser returns razorpay_payment_id / razorpay_order_id / razorpay_signature. The signature is
 * checked (HMAC_SHA256(order_id|payment_id, key_secret)) and then the payment is <b>fetched from
 * Razorpay</b> to confirm order id, amount, currency and status; an "authorized" payment is captured
 * by the server. Nothing is marked paid on client input alone.
 */
@Component
public class RazorpayGateway {
    public static final String NAME = "Razorpay";

    private final AppProperties.Razorpay cfg;
    private final GatewayHttp http;

    public RazorpayGateway(AppProperties props, GatewayHttp http) {
        this.cfg = props.payments().razorpay();
        this.http = http;
    }

    /** A Razorpay payment entity, reduced to the fields we act on. */
    public record RzpPayment(String id, String orderId, long amount, long amountRefunded, String currency,
                             String status, String method, String errorCode, String errorDescription) {
        static RzpPayment of(JsonNode n) {
            return new RzpPayment(n.path("id").asText(null), n.path("order_id").asText(null),
                    n.path("amount").asLong(-1), n.path("amount_refunded").asLong(0),
                    n.path("currency").asText(null), n.path("status").asText(""),
                    n.path("method").asText(null), n.path("error_code").asText(null),
                    n.path("error_description").asText(null));
        }

        public boolean isCaptured() {
            return "captured".equals(status);
        }

        public boolean isAuthorized() {
            return "authorized".equals(status);
        }

        public boolean isFailed() {
            return "failed".equals(status);
        }

        public boolean isFullyRefunded() {
            return "refunded".equals(status) || (amount > 0 && amountRefunded >= amount);
        }
    }

    public boolean configured() {
        return cfg.configured();
    }

    public String keyId() {
        return cfg.keyId();
    }

    /** "live" or "test", derived from the key prefix Razorpay issues. */
    public String mode() {
        return cfg.keyId() != null && cfg.keyId().startsWith("rzp_live_") ? "live" : "test";
    }

    /** POST /v1/orders. The receipt ties the Razorpay order to our order id (max 40 chars). */
    public String createOrder(long orderId, int attempt, long amountPaise) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("currency", "INR");
        body.put("receipt", "sc_order_" + orderId + "_" + attempt);
        body.put("notes", Map.of("smartcart_order_id", String.valueOf(orderId)));
        JsonNode res = http.postJson(NAME, url("/v1/orders"), authHeader(), body);
        String id = res.path("id").asText(null);
        if (id == null || !id.startsWith("order_")) {
            throw new GatewayException(NAME, 502, "no order id in response");
        }
        return id;
    }

    /** GET /v1/payments/{id}: the authoritative state of a payment. */
    public RzpPayment fetchPayment(String paymentId) {
        return RzpPayment.of(http.get(NAME, url("/v1/payments/" + enc(paymentId)), authHeader()));
    }

    /** POST /v1/payments/{id}/capture. Needed when the account does not auto-capture. */
    public RzpPayment capture(String paymentId, long amountPaise) {
        Map<String, Object> body = Map.of("amount", amountPaise, "currency", "INR");
        return RzpPayment.of(http.postJson(NAME, url("/v1/payments/" + enc(paymentId) + "/capture"), authHeader(), body));
    }

    /** POST /v1/payments/{id}/refund (full refund). Returns the refund id (rfnd_...). */
    public String refund(String paymentId, long amountPaise, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("speed", "normal");
        body.put("notes", Map.of("reason", reason == null ? "refund" : reason));
        JsonNode res = http.postJson(NAME, url("/v1/payments/" + enc(paymentId) + "/refund"), authHeader(), body);
        return res.path("id").asText(null);
    }

    /** GET /v1/orders/{id}/payments: every attempt made against a Razorpay order. */
    public List<RzpPayment> fetchOrderPayments(String razorpayOrderId) {
        JsonNode res = http.get(NAME, url("/v1/orders/" + enc(razorpayOrderId) + "/payments"), authHeader());
        List<RzpPayment> out = new ArrayList<>();
        res.path("items").forEach(n -> out.add(RzpPayment.of(n)));
        return out;
    }

    public boolean verifyCheckoutSignature(String razorpayOrderId, String razorpayPaymentId, String signature) {
        if (!configured() || razorpayOrderId == null || razorpayPaymentId == null || signature == null) {
            return false;
        }
        String expected = Crypto.hmacSha256Hex(cfg.keySecret(), razorpayOrderId + "|" + razorpayPaymentId);
        return Crypto.constantTimeEquals(expected, signature);
    }

    public boolean webhookConfigured() {
        return cfg.webhookSecret() != null && !cfg.webhookSecret().isBlank();
    }

    public boolean verifyWebhook(String rawBody, String signature) {
        if (!webhookConfigured() || rawBody == null || signature == null) {
            return false;
        }
        return Crypto.constantTimeEquals(Crypto.hmacSha256Hex(cfg.webhookSecret(), rawBody), signature);
    }

    private String url(String path) {
        return cfg.baseUrl().replaceAll("/+$", "") + path;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Map<String, String> authHeader() {
        String basic = Base64.getEncoder().encodeToString(
                (cfg.keyId() + ":" + cfg.keySecret()).getBytes(StandardCharsets.UTF_8));
        return Map.of("Authorization", "Basic " + basic);
    }
}
