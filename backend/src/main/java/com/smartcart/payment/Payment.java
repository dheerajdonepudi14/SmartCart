package com.smartcart.payment;

import com.smartcart.order.Order;
import jakarta.persistence.*;

import java.time.Instant;

/** One payment attempt at a gateway for an order. An order can have several (retries). */
@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_gateway_order", columnNames = {"gateway", "gateway_order_id"}),
        @UniqueConstraint(name = "uq_payments_gateway_payment", columnNames = {"gateway", "gateway_payment_id"})})
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentGatewayType gateway;

    /** Razorpay order id ("order_...") or PhonePe merchantOrderId. */
    @Column(name = "gateway_order_id", nullable = false, length = 100)
    private String gatewayOrderId;

    /** Razorpay payment id ("pay_...") or PhonePe transactionId, once known. */
    @Column(name = "gateway_payment_id", length = 100)
    private String gatewayPaymentId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.CREATED;

    /** upi / card / netbanking / wallet ..., as reported by the gateway. */
    @Column(length = 30)
    private String method;

    @Column(name = "refund_id", length = 100)
    private String refundId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Payment() {}

    public Payment(Order order, PaymentGatewayType gateway, String gatewayOrderId, long amountPaise) {
        this.order = order;
        this.gateway = gateway;
        this.gatewayOrderId = gatewayOrderId;
        this.amountPaise = amountPaise;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void succeed(String gatewayPaymentId, String method) {
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayPaymentId = gatewayPaymentId;
        this.method = method;
        this.failureReason = null;
    }

    public void fail(String gatewayPaymentId, String reason) {
        this.status = PaymentStatus.FAILED;
        if (gatewayPaymentId != null) {
            this.gatewayPaymentId = gatewayPaymentId;
        }
        this.failureReason = truncate(reason);
    }

    /** A failed attempt whose gateway order is reused for a retry goes back to CREATED. */
    public void reopen() {
        this.status = PaymentStatus.CREATED;
        this.gatewayPaymentId = null;
        this.failureReason = null;
    }

    public void requireRefund(String gatewayPaymentId, String method, String reason) {
        this.status = PaymentStatus.REFUND_PENDING;
        if (gatewayPaymentId != null) {
            this.gatewayPaymentId = gatewayPaymentId;
        }
        if (method != null) {
            this.method = method;
        }
        this.failureReason = truncate(reason);
    }

    public void refunded(String refundId) {
        this.status = PaymentStatus.REFUNDED;
        this.refundId = refundId;
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 500));
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public PaymentGatewayType getGateway() { return gateway; }
    public String getGatewayOrderId() { return gatewayOrderId; }
    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public long getAmountPaise() { return amountPaise; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getMethod() { return method; }
    public String getRefundId() { return refundId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
