package com.smartcart.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcart.common.ApiException;
import com.smartcart.config.AppProperties;
import com.smartcart.order.Order;
import com.smartcart.order.OrderDtos.CheckoutRequest;
import com.smartcart.order.OrderRepository;
import com.smartcart.order.OrderService;
import com.smartcart.order.OrderStatus;
import com.smartcart.order.RefundsRequestedEvent;
import com.smartcart.payment.PaymentDtos.*;
import com.smartcart.payment.PaymentRecorder.Outcome;
import com.smartcart.payment.gateway.GatewayException;
import com.smartcart.payment.gateway.GatewayOrder;
import com.smartcart.payment.gateway.GatewayStatus;
import com.smartcart.payment.gateway.PhonePeGateway;
import com.smartcart.payment.gateway.RazorpayGateway;
import com.smartcart.payment.gateway.RazorpayGateway.RzpPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checkout and real-time payment confirmation. Rules:
 * <ul>
 *   <li>No simulated payments: a gateway without credentials refuses checkout (503).</li>
 *   <li>An order is PAID only after the payment is fetched from the gateway API and its order id,
 *       amount and currency match; client callbacks and webhooks are only triggers.</li>
 *   <li>No duplicates: Idempotency-Key on checkout, the open Razorpay order is reused on retries,
 *       and a second capture for an already-paid order is refunded automatically.</li>
 * </ul>
 * Gateway HTTP calls run outside database transactions.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,64}$");

    private final OrderService orders;
    private final OrderRepository orderRepository;
    private final PaymentRepository payments;
    private final PaymentRecorder recorder;
    private final RazorpayGateway razorpay;
    private final PhonePeGateway phonepe;
    private final WebhookEventRepository webhookEvents;
    private final ObjectMapper mapper;
    private final String frontendUrl;

    public PaymentService(OrderService orders, OrderRepository orderRepository, PaymentRepository payments,
                          PaymentRecorder recorder, RazorpayGateway razorpay, PhonePeGateway phonepe,
                          WebhookEventRepository webhookEvents, ObjectMapper mapper, AppProperties props) {
        this.orders = orders;
        this.orderRepository = orderRepository;
        this.payments = payments;
        this.recorder = recorder;
        this.razorpay = razorpay;
        this.phonepe = phonepe;
        this.webhookEvents = webhookEvents;
        this.mapper = mapper;
        this.frontendUrl = props.frontendUrl().replaceAll("/+$", "");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportConfiguration() {
        if (razorpay.configured()) {
            log.info("Razorpay enabled in {} mode (key {}…); webhook secret {}", razorpay.mode(),
                    razorpay.keyId().substring(0, Math.min(12, razorpay.keyId().length())),
                    razorpay.webhookConfigured() ? "set" : "NOT set - webhooks will be rejected");
        } else {
            log.warn("Razorpay is NOT configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET) - checkout will return 503");
        }
        log.info("PhonePe {}", phonepe.configured() ? "enabled in " + phonepe.mode() + " mode" : "not configured");
    }

    public PaymentConfig config() {
        Map<PaymentGatewayType, GatewayInfo> m = new EnumMap<>(PaymentGatewayType.class);
        m.put(PaymentGatewayType.RAZORPAY, new GatewayInfo(razorpay.configured(),
                razorpay.configured() ? razorpay.mode() : "not_configured"));
        m.put(PaymentGatewayType.PHONEPE, new GatewayInfo(phonepe.configured(),
                phonepe.configured() ? phonepe.mode() : "not_configured"));
        return new PaymentConfig(m, razorpay.configured() ? razorpay.keyId() : null);
    }

    // ------------------------------------------------------------------ checkout

    public CheckoutResponse checkout(Long userId, String userName, String email, CheckoutRequest req,
                                     String idempotencyKey) {
        String key = normaliseKey(idempotencyKey);
        ensureConfigured(req.gateway());

        if (key != null) {
            Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                return resume(userId, existing.get().getId(), userName, email);
            }
        }
        Order order;
        try {
            order = orders.createPendingOrder(userId, req, key);
        } catch (DataIntegrityViolationException race) {
            // Two requests with the same key raced; the unique index let exactly one through.
            if (key == null) {
                throw race;
            }
            Order winner = orderRepository.findByUserIdAndIdempotencyKey(userId, key)
                    .orElseThrow(() -> race);
            return resume(userId, winner.getId(), userName, email);
        }
        try {
            return startPayment(order, req.gateway(), userName, email, false);
        } catch (GatewayException e) {
            // The gateway could not create the payment: release the reserved stock right away.
            orders.cancelByUser(userId, order.getId());
            throw e;
        }
    }

    /** Replays an idempotent checkout: same order, same open gateway order, no new charge. */
    private CheckoutResponse resume(Long userId, Long orderId, String userName, String email) {
        Order order = orders.owned(userId, orderId);
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            return startPayment(order, order.getPaymentGateway(), userName, email, true);
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("The checkout with this Idempotency-Key was cancelled; start a new checkout with a new key");
        }
        return new CheckoutResponse(order.getId(), order.getStatus(), order.getPaymentGateway(), order.totalPaise(),
                "INR", null, null, payUrl(order.getId()), true);
    }

    public CheckoutResponse retry(Long userId, String userName, String email, Long orderId, PaymentGatewayType gateway) {
        Order order = orders.owned(userId, orderId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("This order is no longer awaiting payment (status " + order.getStatus() + ")");
        }
        PaymentGatewayType g = gateway == null ? order.getPaymentGateway() : gateway;
        ensureConfigured(g);
        return startPayment(order, g, userName, email, false);
    }

    private void ensureConfigured(PaymentGatewayType g) {
        if (!configured(g)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    (g == PaymentGatewayType.RAZORPAY ? "Razorpay" : "PhonePe")
                            + " is not configured on the server. Set its API keys in the backend environment.");
        }
    }

    private boolean configured(PaymentGatewayType g) {
        return g == PaymentGatewayType.RAZORPAY ? razorpay.configured() : phonepe.configured();
    }

    private CheckoutResponse startPayment(Order order, PaymentGatewayType gateway, String userName, String email,
                                          boolean replayed) {
        long amount = order.totalPaise();
        if (gateway == PaymentGatewayType.RAZORPAY) {
            String rzpOrderId = openRazorpayOrder(order, amount);
            RazorpayOptions opts = new RazorpayOptions(razorpay.keyId(), rzpOrderId, amount, "INR",
                    "Smart Cart", "Order #" + order.getId(),
                    new Prefill(userName, email, order.getAddress().getPhone()));
            return new CheckoutResponse(order.getId(), order.getStatus(), gateway, amount, "INR", opts, null,
                    payUrl(order.getId()), replayed);
        }
        String merchantOrderId = "sc_" + order.getId() + "_" + System.currentTimeMillis();
        GatewayOrder go = phonepe.createOrder(merchantOrderId, amount,
                frontendUrl + "/orders/" + order.getId() + "?pg=phonepe");
        recorder.recordCreated(order.getId(), gateway, go.gatewayOrderId(), amount);
        log.info("PhonePe payment started orderId={} merchantOrderId={}", order.getId(), merchantOrderId);
        return new CheckoutResponse(order.getId(), order.getStatus(), gateway, amount, "INR", null, go.redirectUrl(),
                payUrl(order.getId()), replayed);
    }

    /**
     * Returns the Razorpay order to pay against. A Razorpay order accepts any number of attempts but
     * at most one successful payment, so while an attempt is open (or failed) we keep reusing it:
     * retries and double-clicks can never produce two chargeable Razorpay orders.
     */
    private String openRazorpayOrder(Order order, long amount) {
        Optional<Payment> latest = payments.findFirstByOrderIdAndGatewayOrderByIdDesc(order.getId(),
                PaymentGatewayType.RAZORPAY);
        if (latest.isPresent() && latest.get().getAmountPaise() == amount
                && (latest.get().getStatus() == PaymentStatus.CREATED || latest.get().getStatus() == PaymentStatus.FAILED)) {
            recorder.reopen(latest.get().getId());
            log.info("Reusing Razorpay order {} for orderId={}", latest.get().getGatewayOrderId(), order.getId());
            return latest.get().getGatewayOrderId();
        }
        int attempt = (int) payments.countByOrderIdAndGateway(order.getId(), PaymentGatewayType.RAZORPAY) + 1;
        String rzpOrderId = razorpay.createOrder(order.getId(), attempt, amount);
        recorder.recordCreated(order.getId(), PaymentGatewayType.RAZORPAY, rzpOrderId, amount);
        log.info("Razorpay order {} created for orderId={} amount={} paise", rzpOrderId, order.getId(), amount);
        return rzpOrderId;
    }

    private String payUrl(Long orderId) {
        return frontendUrl + "/pay/" + orderId;
    }

    private static String normaliseKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim();
        if (!IDEMPOTENCY_KEY.matcher(k).matches()) {
            throw ApiException.badRequest("Idempotency-Key must be 8-64 characters of letters, digits, '-', '_', ':' or '.'");
        }
        return k;
    }

    // ------------------------------------------------------------------ Razorpay confirmation

    /** Browser callback after Checkout: signature check, then authoritative fetch from Razorpay. */
    public PaymentResult confirmRazorpay(Long userId, RazorpayVerifyRequest req) {
        Order order = orders.owned(userId, req.orderId());
        Payment p = payments.findByGatewayAndGatewayOrderId(PaymentGatewayType.RAZORPAY, req.razorpayOrderId())
                .filter(x -> x.getOrder().getId().equals(order.getId()))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!razorpay.verifyCheckoutSignature(req.razorpayOrderId(), req.razorpayPaymentId(), req.razorpaySignature())) {
            log.warn("Razorpay signature mismatch orderId={}", order.getId());
            throw ApiException.badRequest("Payment signature verification failed");
        }
        settleRazorpay(p, req.razorpayPaymentId(), true);
        return result(order.getId(), p.getId(), null);
    }

    /**
     * Fetches the payment from Razorpay, captures it if only authorized, and records the outcome.
     * @param strict throw on mismatches (client call); when false (webhook / reconciler) just log.
     */
    void settleRazorpay(Payment p, String rzpPaymentId, boolean strict) {
        RzpPayment rp;
        try {
            rp = razorpay.fetchPayment(rzpPaymentId);
        } catch (GatewayException e) {
            if (e.isClientError()) {
                log.warn("Razorpay does not know payment {} (order row {})", rzpPaymentId, p.getId());
                if (strict) {
                    throw ApiException.badRequest("Unknown Razorpay payment id");
                }
                return;
            }
            throw e;
        }
        String problem = null;
        if (!p.getGatewayOrderId().equals(rp.orderId())) {
            problem = "Payment " + rp.id() + " belongs to Razorpay order " + rp.orderId() + ", not " + p.getGatewayOrderId();
        } else if (rp.amount() != p.getAmountPaise() || !"INR".equals(rp.currency())) {
            problem = "Amount mismatch for " + rp.id() + ": expected " + p.getAmountPaise() + " INR paise, got "
                    + rp.amount() + " " + rp.currency();
        }
        if (problem != null) {
            log.error(problem);
            if (strict) {
                throw ApiException.badRequest("Payment does not match this order");
            }
            return;
        }

        if (rp.isAuthorized()) {
            try {
                rp = razorpay.capture(rp.id(), rp.amount());
                log.info("Captured authorized Razorpay payment {}", rp.id());
            } catch (GatewayException e) {
                if (!e.isClientError()) {
                    throw e;
                }
                // Typically "already captured" (auto-capture raced us): read the current state.
                rp = razorpay.fetchPayment(rp.id());
            }
        }

        if (rp.isFullyRefunded()) {
            log.info("Razorpay payment {} is already refunded; not applying it to order row {}", rp.id(), p.getId());
        } else if (rp.isCaptured()) {
            Outcome outcome = recorder.markSucceeded(p.getId(), rp.id(), rp.method());
            if (outcome == Outcome.REFUND_NEEDED) {
                refundNow(p.getId());
            } else if (outcome == Outcome.EXTRA_CAPTURE) {
                refundUntracked(rp, "Extra capture on an already-settled order");
            }
        } else if (rp.isFailed()) {
            recorder.markFailed(p.getId(), rp.id(), rp.errorDescription() == null ? "Payment failed" : rp.errorDescription());
        } else {
            log.info("Razorpay payment {} is {}; waiting", rp.id(), rp.status());
        }
    }

    // ------------------------------------------------------------------ PhonePe confirmation

    /** Called when the shopper returns from PhonePe; asks PhonePe for the authoritative state. */
    public PaymentResult syncPhonePe(Long userId, Long orderId) {
        Order order = orders.owned(userId, orderId);
        Payment p = payments.findFirstByOrderIdAndGatewayOrderByIdDesc(order.getId(), PaymentGatewayType.PHONEPE)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.getStatus() == PaymentStatus.CREATED && phonepe.configured()) {
            applyPhonePe(p, phonepe.fetchStatus(p.getGatewayOrderId()));
        }
        return result(orderId, p.getId(), null);
    }

    private void applyPhonePe(Payment p, GatewayStatus s) {
        switch (s.state()) {
            case SUCCEEDED -> {
                Outcome o = recorder.markSucceeded(p.getId(), s.gatewayPaymentId(), "phonepe");
                if (o == Outcome.REFUND_NEEDED || o == Outcome.EXTRA_CAPTURE) {
                    log.error("PhonePe payment {} for order {} needs a manual refund from the PhonePe dashboard",
                            s.gatewayPaymentId(), p.getOrder().getId());
                }
            }
            case FAILED -> recorder.markFailed(p.getId(), s.gatewayPaymentId(), s.reason());
            case PENDING -> { }
        }
    }

    private PaymentResult result(Long orderId, Long paymentRowId, String message) {
        OrderStatus os = orders.getForUser(null, orderId, true).status();
        Payment p = payments.findById(paymentRowId).orElseThrow(() -> ApiException.notFound("Payment"));
        String msg = message != null ? message : switch (p.getStatus()) {
            case SUCCEEDED -> "Payment verified with the gateway and captured";
            case FAILED -> "Payment failed" + (p.getFailureReason() == null ? "" : ": " + p.getFailureReason());
            case REFUND_PENDING -> "Payment will be refunded: " + p.getFailureReason();
            case REFUNDED -> "Payment refunded";
            case CREATED -> "Waiting for the gateway to confirm the payment";
        };
        return new PaymentResult(orderId, os, p.getStatus(), p.getGatewayPaymentId(), msg);
    }

    // ------------------------------------------------------------------ webhooks

    /**
     * Razorpay webhook. The signature proves the event came from Razorpay; the payment is still
     * re-fetched from the API before anything changes, so a replayed or crafted body cannot mark
     * an order paid. Transient gateway errors propagate (HTTP 502) so Razorpay retries delivery.
     */
    public void handleRazorpayWebhook(String rawBody, String signature, String eventId) {
        if (!razorpay.verifyWebhook(rawBody, signature)) {
            throw ApiException.unauthorized("Invalid webhook signature");
        }
        if (eventId != null && webhookEvents.existsByGatewayAndEventId(PaymentGatewayType.RAZORPAY, eventId)) {
            return; // duplicate delivery
        }
        JsonNode root = readJson(rawBody);
        String event = root.path("event").asText();
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String paymentId = payment.path("id").asText(null);
        String rzpOrderId = payment.path("order_id").asText(null);
        if (rzpOrderId == null) {
            rzpOrderId = root.path("payload").path("order").path("entity").path("id").asText(null);
        }
        switch (event) {
            case "payment.authorized", "payment.captured", "payment.failed", "order.paid" -> {
                if (rzpOrderId != null && paymentId != null) {
                    Optional<Payment> row = payments.findByGatewayAndGatewayOrderId(PaymentGatewayType.RAZORPAY, rzpOrderId);
                    if (row.isPresent()) {
                        settleRazorpay(row.get(), paymentId, false);
                    } else {
                        log.info("Razorpay webhook {} for unknown order {}", event, rzpOrderId);
                    }
                }
            }
            default -> log.debug("Ignoring Razorpay event {}", event);
        }
        if (eventId != null) {
            try {
                webhookEvents.save(new WebhookEvent(PaymentGatewayType.RAZORPAY, eventId));
            } catch (DataIntegrityViolationException ignored) {
                // A concurrent delivery already recorded it.
            }
        }
    }

    /** PhonePe webhook: authenticated by SHA256(username:password); state re-fetched from PhonePe. */
    public void handlePhonePeWebhook(String rawBody, String authorization) {
        if (!phonepe.verifyWebhook(authorization)) {
            throw ApiException.unauthorized("Invalid webhook authorization");
        }
        String merchantOrderId = readJson(rawBody).path("payload").path("merchantOrderId").asText(null);
        if (merchantOrderId == null) {
            return;
        }
        payments.findByGatewayAndGatewayOrderId(PaymentGatewayType.PHONEPE, merchantOrderId).ifPresentOrElse(
                p -> applyPhonePe(p, phonepe.fetchStatus(merchantOrderId)),
                () -> log.info("PhonePe webhook for unknown merchantOrderId {}", merchantOrderId));
    }

    // ------------------------------------------------------------------ reconciliation & refunds

    /** Polls the gateway for a stuck payment (missed webhook / closed browser). */
    public void reconcile(Payment p) {
        if (p.getGateway() == PaymentGatewayType.RAZORPAY && razorpay.configured()) {
            List<RzpPayment> attempts = razorpay.fetchOrderPayments(p.getGatewayOrderId());
            Optional<RzpPayment> settled = attempts.stream()
                    .filter(a -> a.isCaptured() || a.isAuthorized()).findFirst();
            if (settled.isPresent()) {
                settleRazorpay(p, settled.get().id(), false);
            }
        } else if (p.getGateway() == PaymentGatewayType.PHONEPE && phonepe.configured()) {
            applyPhonePe(p, phonepe.fetchStatus(p.getGatewayOrderId()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundsRequested(RefundsRequestedEvent event) {
        payments.findByOrderIdAndStatus(event.orderId(), PaymentStatus.REFUND_PENDING)
                .forEach(p -> refundNow(p.getId()));
    }

    public void retryPendingRefunds() {
        payments.findByStatusAndGateway(PaymentStatus.REFUND_PENDING, PaymentGatewayType.RAZORPAY)
                .forEach(p -> refundNow(p.getId()));
    }

    /** Sends a full refund for a REFUND_PENDING Razorpay payment. Safe to call repeatedly. */
    void refundNow(Long paymentRowId) {
        Payment p = payments.findById(paymentRowId).orElse(null);
        if (p == null || p.getStatus() != PaymentStatus.REFUND_PENDING) {
            return;
        }
        if (p.getGateway() != PaymentGatewayType.RAZORPAY || !razorpay.configured() || p.getGatewayPaymentId() == null) {
            log.error("Payment row {} ({}) needs a manual refund", p.getId(), p.getGateway());
            return;
        }
        try {
            RzpPayment rp = razorpay.fetchPayment(p.getGatewayPaymentId());
            if (rp.isFullyRefunded()) {
                recorder.markRefunded(p.getId(), "already_refunded");
                return;
            }
            String refundId = razorpay.refund(rp.id(), rp.amount() - rp.amountRefunded(), p.getFailureReason());
            recorder.markRefunded(p.getId(), refundId);
        } catch (GatewayException e) {
            log.warn("Refund for payment row {} failed, will retry: {}", p.getId(), e.getMessage());
        }
    }

    private void refundUntracked(RzpPayment rp, String reason) {
        try {
            String refundId = razorpay.refund(rp.id(), rp.amount() - rp.amountRefunded(), reason);
            log.warn("Refunded untracked Razorpay payment {} ({})", rp.id(), refundId);
        } catch (GatewayException e) {
            log.error("Could not refund Razorpay payment {} - refund it from the dashboard: {}", rp.id(), e.getMessage());
        }
    }

    private JsonNode readJson(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw ApiException.badRequest("Malformed webhook body");
        }
    }
}
