package com.smartcart.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartcart.common.Crypto;
import com.smartcart.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PhonePe PG Standard Checkout (v2).
 * Flow: fetch an O-Bearer token (POST /v1/oauth/token, client credentials) -> POST /checkout/v2/pay
 * with a unique merchantOrderId -> redirect the shopper to the returned redirectUrl -> PhonePe sends
 * the shopper back to our redirect URL and calls the webhook -> we confirm with
 * GET /checkout/v2/order/{merchantOrderId}/status (states PENDING / COMPLETED / FAILED).
 * Webhooks carry Authorization: SHA256(username:password) configured in the PhonePe dashboard.
 */
@Component
public class PhonePeGateway {
    static final String NAME = "PhonePe";

    private final AppProperties.PhonePe cfg;
    private final GatewayHttp http;

    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public PhonePeGateway(AppProperties props, GatewayHttp http) {
        this.cfg = props.payments().phonepe();
        this.http = http;
    }

    public boolean configured() {
        return cfg.configured();
    }

    public String mode() {
        return cfg.production() ? "production" : "sandbox";
    }

    public GatewayOrder createOrder(String merchantOrderId, long amountPaise, String redirectUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantOrderId", merchantOrderId);
        body.put("amount", amountPaise);
        body.put("expireAfter", 1800);
        body.put("paymentFlow", Map.of(
                "type", "PG_CHECKOUT",
                "message", "Smart Cart order payment",
                "merchantUrls", Map.of("redirectUrl", redirectUrl)));
        JsonNode res = http.postJson(NAME, cfg.pgBaseUrl() + "/checkout/v2/pay", authHeader(), body);
        String url = res.path("redirectUrl").asText(null);
        if (url == null) {
            throw new com.smartcart.common.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "PhonePe did not return a payment page");
        }
        return new GatewayOrder(merchantOrderId, url);
    }

    public GatewayStatus fetchStatus(String merchantOrderId) {
        String url = cfg.pgBaseUrl() + "/checkout/v2/order/"
                + URLEncoder.encode(merchantOrderId, StandardCharsets.UTF_8) + "/status?details=false";
        return toStatus(http.get(NAME, url, authHeader()));
    }

    /** Maps a PhonePe order payload (status API response or webhook "payload") to our model. */
    public static GatewayStatus toStatus(JsonNode order) {
        String state = order.path("state").asText("PENDING").toUpperCase(Locale.ROOT);
        JsonNode details = order.path("paymentDetails");
        JsonNode last = details.isArray() && !details.isEmpty() ? details.get(details.size() - 1) : null;
        String txnId = last == null ? null : last.path("transactionId").asText(null);
        return switch (state) {
            case "COMPLETED" -> new GatewayStatus(GatewayStatus.State.SUCCEEDED, txnId, null);
            case "FAILED" -> new GatewayStatus(GatewayStatus.State.FAILED, txnId,
                    order.path("errorCode").asText(last == null ? "Payment failed" : last.path("errorCode").asText("Payment failed")));
            default -> GatewayStatus.pending();
        };
    }

    public boolean webhookConfigured() {
        return notBlank(cfg.webhookUsername()) && notBlank(cfg.webhookPassword());
    }

    /** PhonePe sends Authorization: SHA256(username:password) (hex). */
    public boolean verifyWebhook(String authorizationHeader) {
        if (!webhookConfigured() || authorizationHeader == null) {
            return false;
        }
        String received = authorizationHeader.trim();
        if (received.regionMatches(true, 0, "SHA256 ", 0, 7)) {
            received = received.substring(7).trim();
        }
        String expected = Crypto.sha256Hex(cfg.webhookUsername() + ":" + cfg.webhookPassword());
        return Crypto.constantTimeEquals(expected, received.toLowerCase(Locale.ROOT));
    }

    private Map<String, String> authHeader() {
        return Map.of("Authorization", "O-Bearer " + token());
    }

    private synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(120))) {
            return cachedToken;
        }
        String form = "client_id=" + enc(cfg.clientId())
                + "&client_version=" + enc(cfg.clientVersion())
                + "&client_secret=" + enc(cfg.clientSecret())
                + "&grant_type=client_credentials";
        JsonNode res = http.postForm(NAME, cfg.authUrl(), form);
        cachedToken = res.path("access_token").asText();
        long expiresAt = res.path("expires_at").asLong(0);
        tokenExpiresAt = expiresAt > 0 ? Instant.ofEpochSecond(expiresAt) : Instant.now().plusSeconds(600);
        return cachedToken;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
