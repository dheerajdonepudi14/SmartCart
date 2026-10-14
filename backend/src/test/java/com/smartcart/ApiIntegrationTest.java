package com.smartcart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcart.common.Crypto;
import com.smartcart.payment.Payment;
import com.smartcart.payment.PaymentGatewayType;
import com.smartcart.payment.PaymentRecorder;
import com.smartcart.payment.PaymentRepository;
import com.smartcart.payment.PaymentStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** End-to-end API tests: MockMvc + H2 + an in-process Razorpay API stub. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    static final RazorpayStub RZP = new RazorpayStub();
    static final String WEBHOOK_SECRET = "test-webhook-secret";

    @DynamicPropertySource
    static void razorpay(DynamicPropertyRegistry r) {
        r.add("app.payments.razorpay.key-id", () -> RazorpayStub.KEY_ID);
        r.add("app.payments.razorpay.key-secret", () -> RazorpayStub.KEY_SECRET);
        r.add("app.payments.razorpay.base-url", RZP::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        RZP.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRecorder recorder;

    private static final Map<String, Object> ADDRESS = Map.of(
            "fullName", "Ravi Kumar", "phone", "9876543210", "line1", "12 MG Road",
            "city", "Tenali", "state", "Andhra Pradesh", "postalCode", "522201");

    // ------------------------------------------------------------------ helpers

    record Session(String token, Cookie refresh) {}

    private Session register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Ravi Kumar", "email", email, "password", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(cookie().httpOnly("sc_refresh", true))
                .andReturn();
        return new Session(body(r).get("accessToken").asText(), r.getResponse().getCookie("sc_refresh"));
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return body(r).get("accessToken").asText();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** Cheapest product: high stock, so repeated test purchases never run out. */
    private long productId() throws Exception {
        MvcResult r = mvc.perform(get("/api/products").param("size", "1").param("sort", "price_asc"))
                .andExpect(status().isOk()).andReturn();
        return body(r).get("content").get(0).get("id").asLong();
    }

    private int stockOf(long productId) throws Exception {
        return body(mvc.perform(get("/api/products/" + productId)).andReturn()).get("stock").asInt();
    }

    private void addToCart(String token, long productId, int qty) throws Exception {
        mvc.perform(post("/api/cart").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("productId", productId, "quantity", qty))))
                .andExpect(status().isOk());
    }

    private JsonNode checkout(String token, String key) throws Exception {
        var req = post("/api/checkout").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("gateway", "RAZORPAY", "address", ADDRESS)));
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return body(mvc.perform(req).andExpect(status().isOk()).andReturn());
    }

    private String orderStatus(String token, long orderId) throws Exception {
        return body(mvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).get("status").asText();
    }

    private MvcResult verify(String token, long orderId, String rzpOrderId, String payId, String signature) throws Exception {
        return mvc.perform(post("/api/payments/razorpay/verify").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("orderId", orderId, "razorpayOrderId", rzpOrderId,
                        "razorpayPaymentId", payId, "razorpaySignature", signature)))).andReturn();
    }

    private static String checkoutSignature(String rzpOrderId, String payId) {
        return Crypto.hmacSha256Hex(RazorpayStub.KEY_SECRET, rzpOrderId + "|" + payId);
    }

    private MvcResult webhook(String event, String payId, String rzpOrderId, long amount, String eventId,
                              String secret) throws Exception {
        String bodyJson = json.writeValueAsString(Map.of("event", event, "payload", Map.of("payment", Map.of("entity",
                Map.of("id", payId, "order_id", rzpOrderId, "amount", amount, "status", "captured")))));
        return mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(bodyJson)
                .header("X-Razorpay-Signature", Crypto.hmacSha256Hex(secret, bodyJson))
                .header("X-Razorpay-Event-Id", eventId)).andReturn();
    }

    /** New user with one item in the cart and a fresh Razorpay checkout. */
    private record Checkout(Session s, long productId, long orderId, String rzpOrderId, long amount) {}

    private Checkout startCheckout() throws Exception {
        Session s = register();
        long pid = productId();
        addToCart(s.token(), pid, 1);
        JsonNode res = checkout(s.token(), UUID.randomUUID().toString());
        return new Checkout(s, pid, res.get("orderId").asLong(), res.at("/razorpay/razorpayOrderId").asText(),
                res.get("amountPaise").asLong());
    }

    // ------------------------------------------------------------------ catalogue, auth, security

    @Test
    void catalogIsPublicAndPaginated() throws Exception {
        mvc.perform(get("/api/products").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.totalElements").isNumber());
        mvc.perform(get("/api/categories")).andExpect(status().isOk());
        mvc.perform(get("/api/search").param("query", "headphones"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").exists());
        mvc.perform(get("/api/payments/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.gateways.RAZORPAY.available").value(true))
                .andExpect(jsonPath("$.gateways.RAZORPAY.mode").value("test"))
                .andExpect(jsonPath("$.gateways.PHONEPE.available").value(false))
                .andExpect(jsonPath("$.razorpayKeyId").value(RazorpayStub.KEY_ID));
        mvc.perform(get("/api/products/stock/stream")).andExpect(request().asyncStarted());
    }

    @Test
    void protectedAndAdminEndpointsEnforceRoles() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cart").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());

        Session user = register();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ROLE_USER"));
        mvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/1").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isForbidden());

        String admin = login("admin@test.local", "Admin@12345");
        mvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
    }

    @Test
    void validationErrorsAreReported() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"bad\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndDetectsReuse() throws Exception {
        Session s = register();
        MvcResult r = mvc.perform(post("/api/auth/refresh").cookie(s.refresh()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty()).andReturn();
        Cookie rotated = r.getResponse().getCookie("sc_refresh");
        assertThat(rotated.getValue()).isNotEqualTo(s.refresh().getValue());

        mvc.perform(post("/api/auth/refresh").cookie(s.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(rotated)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ Razorpay payments

    @Test
    void razorpayCheckoutIsIdempotentAndVerifiedPaymentIsCapturedOnce() throws Exception {
        Session s = register();
        long pid = productId();
        int stockBefore = stockOf(pid);
        addToCart(s.token(), pid, 2);

        String key = UUID.randomUUID().toString();
        int ordersAtRazorpay = RZP.orders.size();
        JsonNode first = checkout(s.token(), key);
        JsonNode replay = checkout(s.token(), key);

        long orderId = first.get("orderId").asLong();
        String rzpOrderId = first.at("/razorpay/razorpayOrderId").asText();
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(first.at("/razorpay/keyId").asText()).isEqualTo(RazorpayStub.KEY_ID);
        assertThat(first.get("payUrl").asText()).endsWith("/pay/" + orderId);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(replay.at("/razorpay/razorpayOrderId").asText()).isEqualTo(rzpOrderId);
        assertThat(RZP.orders.size()).isEqualTo(ordersAtRazorpay + 1);          // one Razorpay order
        assertThat(RZP.orders.get(rzpOrderId).get("amount")).isEqualTo(first.get("amountPaise").asInt());
        assertThat(stockOf(pid)).isEqualTo(stockBefore - 2);                     // stock reserved once
        assertThat(orderStatus(s.token(), orderId)).isEqualTo("PENDING_PAYMENT");

        // Shopper pays in Razorpay Checkout; the account does not auto-capture -> "authorized".
        RZP.addPayment("pay_OK1", rzpOrderId, first.get("amountPaise").asLong(), "authorized");
        MvcResult ok = verify(s.token(), orderId, rzpOrderId, "pay_OK1", checkoutSignature(rzpOrderId, "pay_OK1"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("orderStatus").asText()).isEqualTo("PAID");
        assertThat(body(ok).get("paymentStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(RZP.captured).containsOnlyOnce("pay_OK1");

        // Re-sending the same callback (double submit) changes nothing.
        verify(s.token(), orderId, rzpOrderId, "pay_OK1", checkoutSignature(rzpOrderId, "pay_OK1"));
        assertThat(RZP.captured).containsOnlyOnce("pay_OK1");
        assertThat(RZP.refunded).doesNotContain("pay_OK1");

        mvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + s.token()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.latestPayment.gatewayPaymentId").value("pay_OK1"))
                .andExpect(jsonPath("$.latestPayment.method").value("upi"));
        mvc.perform(get("/api/cart").header("Authorization", "Bearer " + s.token()))
                .andExpect(jsonPath("$.items.length()").value(0));

        // Replaying the checkout key after payment returns the paid order, not a new one.
        JsonNode after = checkout(s.token(), key);
        assertThat(after.get("orderStatus").asText()).isEqualTo("PAID");
        assertThat(RZP.orders.size()).isEqualTo(ordersAtRazorpay + 1);

        // Verified-purchase review, then admin ships the order.
        mvc.perform(post("/api/reviews").header("Authorization", "Bearer " + s.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("productId", pid, "rating", 5, "comment", "Great"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verifiedPurchase").value(true));
        String admin = login("admin@test.local", "Admin@12345");
        mvc.perform(put("/api/admin/orders/" + orderId + "/status").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));

        // Another user cannot see this order.
        Session other = register();
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ CO5 background jobs

    private JsonNode jobsFor(String admin, long orderId) throws Exception {
        return body(mvc.perform(get("/api/jobs/order/" + orderId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
    }

    /** Polls until every job for the order reached COMPLETED or FAILED (they run on the async pool). */
    private JsonNode awaitJobs(String admin, long orderId, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode jobs;
        do {
            Thread.sleep(100);
            jobs = jobsFor(admin, orderId);
        } while ((jobs.size() < expected || jobs.findValuesAsText("status").stream()
                .anyMatch(st -> st.equals("PENDING") || st.equals("PROCESSING")))
                && System.currentTimeMillis() < deadline);
        return jobs;
    }

    @Test
    void verifiedPaymentQueuesIndependentBackgroundJobs() throws Exception {
        Checkout c = startCheckout();
        String admin = login("admin@test.local", "Admin@12345");
        assertThat(jobsFor(admin, c.orderId()).size()).isZero();             // nothing before payment

        RZP.addPayment("pay_JOBS", c.rzpOrderId(), c.amount(), "captured");
        verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_JOBS", checkoutSignature(c.rzpOrderId(), "pay_JOBS"));

        JsonNode jobs = awaitJobs(admin, c.orderId(), 3);
        Map<String, String> statusByType = new java.util.HashMap<>();
        jobs.forEach(j -> statusByType.put(j.get("jobType").asText(), j.get("status").asText()));
        // SMTP is off in tests: the email job fails on its own while the others still complete.
        assertThat(statusByType).containsEntry("EMAIL", "FAILED")
                .containsEntry("INVOICE", "COMPLETED")
                .containsEntry("NOTIFICATION", "COMPLETED");

        mvc.perform(get("/api/jobs/invoices/" + c.orderId()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invoice INV-" + c.orderId())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pay_JOBS")));

        // A repeated verification does not queue the jobs twice.
        verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_JOBS", checkoutSignature(c.rzpOrderId(), "pay_JOBS"));
        assertThat(awaitJobs(admin, c.orderId(), 3).size()).isEqualTo(3);

        long emailJob = 0;
        for (JsonNode j : jobs) {
            if (j.get("jobType").asText().equals("EMAIL")) emailJob = j.get("jobId").asLong();
        }
        mvc.perform(post("/api/jobs/" + emailJob + "/retry").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        mvc.perform(get("/api/jobs").header("Authorization", "Bearer " + c.s().token()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedOrMismatchedPaymentsNeverMarkAnOrderPaid() throws Exception {
        Checkout c = startCheckout();

        // 1. Wrong signature.
        RZP.addPayment("pay_REAL", c.rzpOrderId(), c.amount(), "captured");
        assertThat(verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_REAL", "deadbeef").getResponse().getStatus())
                .isEqualTo(400);

        // 2. Correctly signed, but the payment id does not exist at Razorpay.
        assertThat(verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_FAKE",
                checkoutSignature(c.rzpOrderId(), "pay_FAKE")).getResponse().getStatus()).isEqualTo(400);

        // 3. Real payment, but for a smaller amount.
        RZP.addPayment("pay_CHEAP", c.rzpOrderId(), 100, "captured");
        assertThat(verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_CHEAP",
                checkoutSignature(c.rzpOrderId(), "pay_CHEAP")).getResponse().getStatus()).isEqualTo(400);

        // 4. Webhook with a valid signature describing a payment Razorpay never saw.
        assertThat(webhook("payment.captured", "pay_GHOST", c.rzpOrderId(), c.amount(), "evt_ghost", WEBHOOK_SECRET)
                .getResponse().getStatus()).isEqualTo(200);

        // 5. Webhook with a bad signature.
        assertThat(webhook("payment.captured", "pay_REAL", c.rzpOrderId(), c.amount(), "evt_bad", "wrong-secret")
                .getResponse().getStatus()).isEqualTo(401);

        assertThat(orderStatus(c.s().token(), c.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void webhookConfirmsPaymentWhenBrowserNeverReturns() throws Exception {
        Checkout c = startCheckout();
        RZP.addPayment("pay_WH1", c.rzpOrderId(), c.amount(), "captured");
        for (int i = 0; i < 2; i++) { // second delivery is a harmless duplicate
            assertThat(webhook("payment.captured", "pay_WH1", c.rzpOrderId(), c.amount(), "evt_wh1", WEBHOOK_SECRET)
                    .getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(orderStatus(c.s().token(), c.orderId())).isEqualTo("PAID");
        assertThat(RZP.refunded).doesNotContain("pay_WH1");
    }

    @Test
    void failedAttemptKeepsOrderPayableAndRetryReusesTheSameRazorpayOrder() throws Exception {
        Checkout c = startCheckout();
        RZP.addPayment("pay_DECLINED", c.rzpOrderId(), c.amount(), "failed");
        webhook("payment.failed", "pay_DECLINED", c.rzpOrderId(), c.amount(), "evt_fail1", WEBHOOK_SECRET);

        mvc.perform(get("/api/orders/" + c.orderId()).header("Authorization", "Bearer " + c.s().token()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.latestPayment.status").value("FAILED"));

        int ordersAtRazorpay = RZP.orders.size();
        MvcResult retry = mvc.perform(post("/api/orders/" + c.orderId() + "/pay")
                        .header("Authorization", "Bearer " + c.s().token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gateway\":\"RAZORPAY\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(retry).at("/razorpay/razorpayOrderId").asText()).isEqualTo(c.rzpOrderId());
        assertThat(RZP.orders.size()).isEqualTo(ordersAtRazorpay);

        // Second attempt succeeds (auto-captured account).
        RZP.addPayment("pay_SECOND", c.rzpOrderId(), c.amount(), "captured");
        MvcResult ok = verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_SECOND",
                checkoutSignature(c.rzpOrderId(), "pay_SECOND"));
        assertThat(body(ok).get("orderStatus").asText()).isEqualTo("PAID");
    }

    @Test
    void secondCaptureForAnAlreadyPaidOrderIsRefundedAutomatically() throws Exception {
        Checkout c = startCheckout();
        // A second Razorpay order for the same shop order (e.g. an old tab) - both get paid.
        String extraRzpOrder = "order_EXTRA_" + c.orderId();
        RZP.orders.put(extraRzpOrder, Map.of("id", extraRzpOrder, "amount", c.amount()));
        Payment extra = recorder.recordCreated(c.orderId(), PaymentGatewayType.RAZORPAY, extraRzpOrder, c.amount());

        RZP.addPayment("pay_FIRST", c.rzpOrderId(), c.amount(), "captured");
        verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_FIRST", checkoutSignature(c.rzpOrderId(), "pay_FIRST"));
        assertThat(orderStatus(c.s().token(), c.orderId())).isEqualTo("PAID");

        RZP.addPayment("pay_DUP", extraRzpOrder, c.amount(), "captured");
        webhook("payment.captured", "pay_DUP", extraRzpOrder, c.amount(), "evt_dup", WEBHOOK_SECRET);

        assertThat(RZP.refunded).contains("pay_DUP").doesNotContain("pay_FIRST");
        Payment reloaded = payments.findById(extra.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reloaded.getRefundId()).isEqualTo("rfnd_pay_DUP");
        assertThat(orderStatus(c.s().token(), c.orderId())).isEqualTo("PAID");
    }

    @Test
    void adminCancellingAPaidOrderRefundsThePayment() throws Exception {
        Checkout c = startCheckout();
        RZP.addPayment("pay_CANCEL", c.rzpOrderId(), c.amount(), "captured");
        verify(c.s().token(), c.orderId(), c.rzpOrderId(), "pay_CANCEL", checkoutSignature(c.rzpOrderId(), "pay_CANCEL"));
        int stock = stockOf(c.productId());

        String admin = login("admin@test.local", "Admin@12345");
        mvc.perform(put("/api/admin/orders/" + c.orderId() + "/status").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(RZP.refunded).contains("pay_CANCEL");
        assertThat(stockOf(c.productId())).isEqualTo(stock + 1);
        mvc.perform(get("/api/orders/" + c.orderId()).header("Authorization", "Bearer " + c.s().token()))
                .andExpect(jsonPath("$.latestPayment.status").value("REFUNDED"));
    }

    @Test
    void cancellingUnpaidOrderReleasesStockAndUnconfiguredGatewayIsRefused() throws Exception {
        Checkout c = startCheckout();
        int before = stockOf(c.productId());
        mvc.perform(post("/api/orders/" + c.orderId() + "/cancel").header("Authorization", "Bearer " + c.s().token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stockOf(c.productId())).isEqualTo(before + 1);

        Session s = register();
        addToCart(s.token(), c.productId(), 1);
        mvc.perform(post("/api/checkout").header("Authorization", "Bearer " + s.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("gateway", "PHONEPE", "address", ADDRESS))))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/checkout").header("Authorization", "Bearer " + s.token())
                        .header("Idempotency-Key", "bad key!")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("gateway", "RAZORPAY", "address", ADDRESS))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyCartCannotCheckout() throws Exception {
        Session s = register();
        mvc.perform(post("/api/checkout").header("Authorization", "Bearer " + s.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("gateway", "RAZORPAY", "address", ADDRESS))))
                .andExpect(status().isBadRequest());
    }
}
