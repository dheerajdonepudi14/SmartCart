package com.smartcart.payment;

import com.smartcart.order.OrderService;
import com.smartcart.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Safety net for real-time payments, every minute:
 * 1. asks the gateway about attempts still CREATED after 2 minutes (missed webhook / closed tab);
 * 2. retries refunds that could not be sent earlier;
 * 3. cancels orders still unpaid after 35 minutes, returning their reserved stock.
 * Step 1 always runs before step 3, so a payment made just before expiry is not lost.
 */
@Component
public class PaymentMaintenanceJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentMaintenanceJob.class);
    static final Duration RECONCILE_AFTER = Duration.ofMinutes(2);
    static final Duration EXPIRE_AFTER = Duration.ofMinutes(35);

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final OrderService orders;

    public PaymentMaintenanceJob(PaymentRepository payments, PaymentService paymentService, OrderService orders) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.orders = orders;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        Instant now = Instant.now();
        for (Payment p : payments.findPendingForReconciliation(PaymentStatus.CREATED, OrderStatus.PENDING_PAYMENT,
                now.minus(RECONCILE_AFTER))) {
            try {
                paymentService.reconcile(p);
            } catch (RuntimeException e) {
                log.warn("Reconciliation failed for payment row {}: {}", p.getId(), e.getMessage());
            }
        }
        try {
            paymentService.retryPendingRefunds();
        } catch (RuntimeException e) {
            log.warn("Refund retry failed: {}", e.getMessage());
        }
        orders.expireUnpaid(now.minus(EXPIRE_AFTER));
    }
}
