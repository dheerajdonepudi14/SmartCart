package com.smartcart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for api.razorpay.com used only by the test suite: implements the five
 * endpoints the backend calls, with the same JSON shapes and error format as Razorpay.
 */
public final class RazorpayStub {
    public static final String KEY_ID = "rzp_test_stubkey";
    public static final String KEY_SECRET = "stub_key_secret";

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger orderSeq = new AtomicInteger();
    public final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    public final List<String> captured = Collections.synchronizedList(new ArrayList<>());
    public final List<String> refunded = Collections.synchronizedList(new ArrayList<>());
    public volatile String lastAuthHeader;

    public RazorpayStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Registers a payment made by a "shopper" against a Razorpay order. */
    public void addPayment(String paymentId, String orderId, long amount, String status) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", paymentId);
        p.put("entity", "payment");
        p.put("order_id", orderId);
        p.put("amount", amount);
        p.put("amount_refunded", 0);
        p.put("currency", "INR");
        p.put("status", status);
        p.put("method", "upi");
        if ("failed".equals(status)) {
            p.put("error_code", "BAD_REQUEST_ERROR");
            p.put("error_description", "Payment declined by bank");
        }
        payments.put(paymentId, p);
    }

    private void handle(HttpExchange ex) throws IOException {
        lastAuthHeader = ex.getRequestHeaders().getFirst("Authorization");
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String[] parts = path.split("/"); // "", "v1", "orders"|"payments", id, action
        try {
            if (!("Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8)))
                    .equals(lastAuthHeader)) {
                error(ex, 401, "Authentication failed");
                return;
            }
            if ("POST".equals(method) && path.equals("/v1/orders")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = json.readValue(ex.getRequestBody(), Map.class);
                String id = "order_STUB" + orderSeq.incrementAndGet();
                Map<String, Object> o = new LinkedHashMap<>(body);
                o.put("id", id);
                o.put("entity", "order");
                o.put("status", "created");
                orders.put(id, o);
                ok(ex, o);
            } else if ("GET".equals(method) && parts.length == 5 && parts[2].equals("orders") && parts[4].equals("payments")) {
                List<Object> items = payments.values().stream().filter(p -> parts[3].equals(p.get("order_id")))
                        .map(p -> (Object) p).toList();
                ok(ex, Map.of("entity", "collection", "count", items.size(), "items", items));
            } else if (parts.length >= 4 && parts[2].equals("payments")) {
                Map<String, Object> p = payments.get(parts[3]);
                if (p == null) {
                    error(ex, 400, "The id provided does not exist");
                } else if ("GET".equals(method) && parts.length == 4) {
                    ok(ex, p);
                } else if ("POST".equals(method) && parts.length == 5 && parts[4].equals("capture")) {
                    if (!"authorized".equals(p.get("status"))) {
                        error(ex, 400, "This payment has already been captured");
                        return;
                    }
                    p.put("status", "captured");
                    captured.add(parts[3]);
                    ok(ex, p);
                } else if ("POST".equals(method) && parts.length == 5 && parts[4].equals("refund")) {
                    if (!"captured".equals(p.get("status"))) {
                        error(ex, 400, "Payment is not captured");
                        return;
                    }
                    p.put("status", "refunded");
                    p.put("amount_refunded", p.get("amount"));
                    refunded.add(parts[3]);
                    ok(ex, Map.of("id", "rfnd_" + parts[3], "entity", "refund", "payment_id", parts[3]));
                } else {
                    error(ex, 404, "Not found");
                }
            } else {
                error(ex, 404, "Not found");
            }
        } catch (RuntimeException e) {
            error(ex, 500, e.toString());
        }
    }

    private void ok(HttpExchange ex, Object body) throws IOException {
        send(ex, 200, json.writeValueAsBytes(body));
    }

    private void error(HttpExchange ex, int status, String description) throws IOException {
        send(ex, status, json.writeValueAsBytes(Map.of("error",
                Map.of("code", "BAD_REQUEST_ERROR", "description", description))));
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public void stop() {
        server.stop(0);
    }
}
