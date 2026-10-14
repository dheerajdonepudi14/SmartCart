package com.smartcart.payment;

import com.smartcart.payment.gateway.PhonePeGateway;
import com.smartcart.payment.gateway.RazorpayGateway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports gateway configuration under /actuator/health. It deliberately never reports DOWN: a
 * gateway outage should surface as alerts, not make Kubernetes restart healthy API pods.
 */
@Component("payments")
public class PaymentsHealthIndicator implements HealthIndicator {
    private final RazorpayGateway razorpay;
    private final PhonePeGateway phonepe;

    public PaymentsHealthIndicator(RazorpayGateway razorpay, PhonePeGateway phonepe) {
        this.razorpay = razorpay;
        this.phonepe = phonepe;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("razorpay", razorpay.configured() ? razorpay.mode() : "not configured")
                .withDetail("razorpayWebhook", razorpay.webhookConfigured())
                .withDetail("phonepe", phonepe.configured() ? phonepe.mode() : "not configured")
                .withDetail("phonepeWebhook", phonepe.webhookConfigured())
                .build();
    }
}
