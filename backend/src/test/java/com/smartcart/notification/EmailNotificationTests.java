package com.smartcart.notification;

import com.smartcart.notification.EmailTemplates.Line;
import com.smartcart.notification.EmailTemplates.OrderData;
import com.smartcart.notification.EmailTemplates.OrderEmailKind;
import com.smartcart.order.OrderStatus;
import com.smartcart.order.OrderStatusChangedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailNotificationTests {

    private static OrderStatusChangedEvent event(OrderStatus status, String paymentStatus) {
        return new OrderStatusChangedEvent(1L, 2L, status, paymentStatus, "msg", Instant.now());
    }

    private static OrderData order(String customer, String message) {
        return new OrderData(42, customer, List.of(new Line("Mug <b>", 2, new BigDecimal("398.00"))),
                new BigDecimal("398.00"), new BigDecimal("49.00"), new BigDecimal("447.00"),
                List.of("Asha Rao", "12 MG Road", "Bengaluru, KA 560001"), message,
                "http://localhost:3000/orders/42", "http://localhost:3000/pay/42");
    }

    @Test
    void picksEmailForCustomerFacingEventsOnly() {
        assertThat(EmailNotificationService.kindFor(event(OrderStatus.PENDING_PAYMENT, null))).isNull();
        assertThat(EmailNotificationService.kindFor(event(OrderStatus.PENDING_PAYMENT, "FAILED")))
                .isEqualTo(OrderEmailKind.PAYMENT_FAILED);
        // The payment confirmation is sent by the EMAIL background job, not by this listener.
        assertThat(EmailNotificationService.kindFor(event(OrderStatus.PAID, "SUCCEEDED"))).isNull();
        assertThat(EmailNotificationService.kindFor(event(OrderStatus.SHIPPED, null))).isEqualTo(OrderEmailKind.SHIPPED);
        assertThat(EmailNotificationService.kindFor(event(OrderStatus.DELIVERED, null))).isEqualTo(OrderEmailKind.DELIVERED);
        assertThat(EmailNotificationService.kindFor(event(OrderStatus.CANCELLED, null))).isEqualTo(OrderEmailKind.CANCELLED);
    }

    @Test
    void orderConfirmationListsItemsTotalsAndEscapesHtml() {
        var email = EmailTemplates.order(OrderEmailKind.PAYMENT_RECEIVED, order("<script>x</script>", null));
        assertThat(email.subject()).isEqualTo("Order #42 confirmed - payment received");
        assertThat(email.html())
                .contains("Mug &lt;b&gt;", "&lt;script&gt;", "₹447.00", "₹49.00", "http://localhost:3000/orders/42")
                .doesNotContain("<script>");
        assertThat(email.text()).contains("2 x Mug <b>", "Total:    ₹447.00");
    }

    @Test
    void paymentFailedLinksToRetry() {
        var email = EmailTemplates.order(OrderEmailKind.PAYMENT_FAILED, order("Asha", "Card declined"));
        assertThat(email.subject()).isEqualTo("Payment failed for order #42");
        assertThat(email.html()).contains("Card declined", "Retry payment", "http://localhost:3000/pay/42");
    }

    @Test
    void welcomeEmail() {
        var email = EmailTemplates.welcome("Asha", "http://localhost:3000/");
        assertThat(email.subject()).isEqualTo("Welcome to Smart Cart");
        assertThat(email.html()).contains("Hi Asha,", "http://localhost:3000/");
    }
}
