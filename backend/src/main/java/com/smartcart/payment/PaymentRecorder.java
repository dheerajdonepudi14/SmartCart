package com.smartcart.payment;

import com.smartcart.common.ApiException;
import com.smartcart.order.Order;
import com.smartcart.order.OrderRepository;
import com.smartcart.order.OrderService;
import com.smartcart.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;

/**
 * Transactional state changes for payments. Every confirmation path (browser callback, webhook,
 * status poll, reconciler) ends here and takes a row lock on the payment, so a captured payment is
 * applied to its order exactly once, and a second capture for an already-paid order is flagged for
 * refund instead of being counted twice.
 */
@Component
public class PaymentRecorder {
    private static final Logger log = LoggerFactory.getLogger(PaymentRecorder.class);

    public enum Outcome {
        /** This call moved the order to PAID. */
        APPLIED,
        /** Already recorded earlier (duplicate callback / webhook retry) - nothing changed. */
        ALREADY_APPLIED,
        /** Money was captured but must go back (order already paid, or cancelled and unfulfillable). */
        REFUND_NEEDED,
        /** The row is settled with a different gateway payment id; that extra capture must be refunded. */
        EXTRA_CAPTURE
    }

    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final OrderService orderService;

    public PaymentRecorder(PaymentRepository payments, OrderRepository orders, OrderService orderService) {
        this.payments = payments;
        this.orders = orders;
        this.orderService = orderService;
    }

    @Transactional
    public Payment recordCreated(Long orderId, PaymentGatewayType gateway, String gatewayOrderId, long amountPaise) {
        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("Order is no longer awaiting payment");
        }
        order.setPaymentGateway(gateway);
        return payments.save(new Payment(order, gateway, gatewayOrderId, amountPaise));
    }

    /** Re-opens a failed attempt whose gateway order is being reused for a retry. */
    @Transactional
    public void reopen(Long paymentId) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.getStatus() == PaymentStatus.FAILED) {
            p.reopen();
        }
        p.getOrder().setPaymentGateway(p.getGateway());
    }

    @Transactional
    public Outcome markSucceeded(Long paymentId, String gatewayPaymentId, String method) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        switch (p.getStatus()) {
            case SUCCEEDED -> {
                if (gatewayPaymentId == null || gatewayPaymentId.equals(p.getGatewayPaymentId())) {
                    return Outcome.ALREADY_APPLIED;
                }
                log.error("Second capture {} on settled payment row {} (order {})", gatewayPaymentId, p.getId(),
                        p.getOrder().getId());
                return Outcome.EXTRA_CAPTURE;
            }
            case REFUND_PENDING, REFUNDED -> {
                return gatewayPaymentId == null || gatewayPaymentId.equals(p.getGatewayPaymentId())
                        ? Outcome.ALREADY_APPLIED : Outcome.EXTRA_CAPTURE;
            }
            case CREATED, FAILED -> { }
        }
        Order order = p.getOrder();
        long otherSettled = payments.countOtherWithStatus(order.getId(), p.getId(),
                EnumSet.of(PaymentStatus.SUCCEEDED));
        if (otherSettled > 0) {
            p.requireRefund(gatewayPaymentId, method, "Duplicate payment: order was already paid");
            log.warn("Duplicate payment {} for order {} flagged for refund", gatewayPaymentId, order.getId());
            orderService.notifyPaymentFailed(order, "A duplicate payment was detected and will be refunded");
            return Outcome.REFUND_NEEDED;
        }
        p.succeed(gatewayPaymentId, method);
        if (!orderService.applyPaymentSuccess(order)) {
            p.requireRefund(gatewayPaymentId, method, "Order was cancelled and stock is no longer available");
            return Outcome.REFUND_NEEDED;
        }
        log.info("Payment captured paymentRow={} orderId={} gateway={} id={}", p.getId(), order.getId(),
                p.getGateway(), gatewayPaymentId);
        return Outcome.APPLIED;
    }

    @Transactional
    public PaymentStatus markFailed(Long paymentId, String gatewayPaymentId, String reason) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.getStatus() != PaymentStatus.CREATED) {
            // Never downgrade a success or a refund; repeated failures are no-ops.
            return p.getStatus();
        }
        p.fail(gatewayPaymentId, reason);
        orderService.notifyPaymentFailed(p.getOrder(), "Payment failed: " + (reason == null ? "declined" : reason));
        log.info("Payment failed paymentRow={} orderId={} reason={}", p.getId(), p.getOrder().getId(), reason);
        return p.getStatus();
    }

    /** Runs in its own transaction so it also works from after-commit listeners. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefunded(Long paymentId, String refundId) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.getStatus() == PaymentStatus.REFUND_PENDING) {
            p.refunded(refundId);
            log.info("Payment refunded paymentRow={} orderId={} refundId={}", p.getId(), p.getOrder().getId(), refundId);
        }
    }
}
