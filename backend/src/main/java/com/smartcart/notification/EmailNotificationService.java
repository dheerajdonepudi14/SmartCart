package com.smartcart.notification;

import com.smartcart.auth.UserRegisteredEvent;
import com.smartcart.config.AppProperties;
import com.smartcart.config.AsyncConfig;
import com.smartcart.notification.EmailTemplates.Email;
import com.smartcart.notification.EmailTemplates.Line;
import com.smartcart.notification.EmailTemplates.OrderData;
import com.smartcart.notification.EmailTemplates.OrderEmailKind;
import com.smartcart.order.Order;
import com.smartcart.order.OrderRepository;
import com.smartcart.order.OrderStatusChangedEvent;
import com.smartcart.order.ShippingAddress;
import com.smartcart.user.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Emails customers about their account and orders. Runs after commit on a background thread, so a
 * slow or broken SMTP server never delays or rolls back a checkout; failures are only logged.
 */
@Component
public class EmailNotificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailProperties mail;
    private final String frontendUrl;
    private final UserRepository users;
    private final OrderRepository orders;
    private final TransactionTemplate readTx;

    public EmailNotificationService(ObjectProvider<JavaMailSender> mailSender, MailProperties mail,
                                    AppProperties props, UserRepository users, OrderRepository orders,
                                    PlatformTransactionManager txManager) {
        this.mailSender = mailSender;
        this.mail = mail;
        this.frontendUrl = props.frontendUrl().replaceAll("/+$", "");
        this.users = users;
        this.orders = orders;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    @Async(AsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        if (!mail.enabled()) {
            return;
        }
        users.findById(event.userId()).ifPresent(u ->
                send(u.getEmail(), EmailTemplates.welcome(u.getName(), frontendUrl + "/")));
    }

    @Async(AsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        OrderEmailKind kind = kindFor(event);
        if (!mail.enabled() || kind == null) {
            return;
        }
        render(event.orderId(), kind, event.message()).ifPresent(o -> send(o.to(), o.email()));
    }

    /**
     * Sends the "payment received" confirmation for a paid order. Called by the EMAIL background
     * job, which records the outcome, so unlike the other emails a failure is thrown, not swallowed.
     */
    public void sendOrderConfirmation(Long orderId) throws Exception {
        if (!mail.enabled()) {
            throw new IllegalStateException("Email is disabled (MAIL_ENABLED=false)");
        }
        Outgoing out = render(orderId, OrderEmailKind.PAYMENT_RECEIVED, null)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " not found"));
        sendOrThrow(out.to(), out.email());
    }

    /**
     * Only events a customer should hear about by email; "order placed, awaiting payment" is not
     * one, and the payment confirmation is sent by the EMAIL background job instead.
     */
    static OrderEmailKind kindFor(OrderStatusChangedEvent e) {
        if ("FAILED".equals(e.paymentStatus())) {
            return OrderEmailKind.PAYMENT_FAILED;
        }
        return switch (e.status()) {
            case SHIPPED -> OrderEmailKind.SHIPPED;
            case DELIVERED -> OrderEmailKind.DELIVERED;
            case CANCELLED -> OrderEmailKind.CANCELLED;
            case PENDING_PAYMENT, PAID -> null;
        };
    }

    private record Outgoing(String to, Email email) {}

    private Optional<Outgoing> render(Long orderId, OrderEmailKind kind, String message) {
        // The order's lazy associations need a session; this thread has none of its own.
        return readTx.execute(tx -> orders.findDetailedById(orderId)
                .map(o -> new Outgoing(o.getUser().getEmail(), EmailTemplates.order(kind, orderData(o, message)))));
    }

    private OrderData orderData(Order o, String message) {
        List<Line> lines = o.getItems().stream()
                .map(i -> new Line(i.getProductName(), i.getQuantity(), i.lineTotal()))
                .toList();
        ShippingAddress a = o.getAddress();
        List<String> address = new ArrayList<>();
        String name = o.getUser().getName();
        if (a != null) {
            address.add(a.getFullName());
            address.add(a.getLine1());
            if (a.getLine2() != null && !a.getLine2().isBlank()) {
                address.add(a.getLine2());
            }
            address.add(a.getCity() + ", " + a.getState() + " " + a.getPostalCode());
        }
        return new OrderData(o.getId(), name, lines, o.getSubtotal(), o.getShippingFee(), o.getTotalAmount(),
                address, message, frontendUrl + "/orders/" + o.getId(), frontendUrl + "/pay/" + o.getId());
    }

    private void send(String to, Email email) {
        try {
            sendOrThrow(to, email);
        } catch (Exception e) {
            log.error("Email failed subject=\"{}\": {}", email.subject(), e.getMessage());
        }
    }

    private void sendOrThrow(String to, Email email) throws Exception {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("No SMTP server is configured (spring.mail.host)");
        }
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setFrom(mail.from(), mail.fromName());
        helper.setTo(to);
        helper.setSubject(email.subject());
        helper.setText(email.text(), email.html());
        sender.send(msg);
        log.info("Email sent subject=\"{}\"", email.subject());
    }
}
