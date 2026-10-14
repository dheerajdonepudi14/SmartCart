package com.smartcart.job;

import com.smartcart.config.AsyncConfig;
import com.smartcart.job.InvoiceRenderer.InvoiceData;
import com.smartcart.job.InvoiceRenderer.Line;
import com.smartcart.notification.EmailNotificationService;
import com.smartcart.order.Order;
import com.smartcart.order.OrderRepository;
import com.smartcart.order.ShippingAddress;
import com.smartcart.payment.Payment;
import com.smartcart.payment.PaymentRepository;
import com.smartcart.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Background worker: runs one job on the async pool, moving it PENDING -> PROCESSING ->
 * COMPLETED | FAILED, saving every step to PostgreSQL and pushing it over WebSocket. Each job is
 * independent, so a failed email never stops the invoice or notification.
 */
@Service
public class BackgroundJobProcessor {
    private static final Logger log = LoggerFactory.getLogger(BackgroundJobProcessor.class);

    private final BackgroundJobRepository jobs;
    private final JobNotificationService notifier;
    private final EmailNotificationService email;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final TransactionTemplate readTx;
    private final Path invoiceDir;

    public BackgroundJobProcessor(BackgroundJobRepository jobs, JobNotificationService notifier,
                                  EmailNotificationService email, OrderRepository orders, PaymentRepository payments,
                                  PlatformTransactionManager txManager,
                                  @Value("${app.invoices.dir:invoices}") String invoiceDir) {
        this.jobs = jobs;
        this.notifier = notifier;
        this.email = email;
        this.orders = orders;
        this.payments = payments;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.invoiceDir = Paths.get(invoiceDir);
    }

    /** Must be called from another bean so the @Async proxy applies. */
    @Async(AsyncConfig.EXECUTOR)
    public void process(Long jobId) {
        BackgroundJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        try {
            job = update(job, JobStatus.PROCESSING, "Task started");
            String result = switch (job.getJobType()) {
                case EMAIL -> sendOrderEmail(job.getOrderId());
                case INVOICE -> generateInvoice(job.getOrderId());
                case NOTIFICATION -> sendNotification(job.getOrderId());
            };
            update(job, JobStatus.COMPLETED, result);
        } catch (Exception e) {
            log.warn("Job {} {} for order {} failed: {}", job.getJobId(), job.getJobType(), job.getOrderId(), reason(e));
            update(job, JobStatus.FAILED, reason(e));
        }
    }

    private BackgroundJob update(BackgroundJob job, JobStatus status, String message) {
        job.moveTo(status, message);
        BackgroundJob saved = jobs.save(job);
        notifier.publish(saved);
        return saved;
    }

    private String sendOrderEmail(Long orderId) throws Exception {
        email.sendOrderConfirmation(orderId);
        return "Order confirmation email sent";
    }

    private String generateInvoice(Long orderId) throws IOException {
        InvoiceData data = readTx.execute(tx -> orders.findDetailedById(orderId).map(this::invoiceData).orElse(null));
        if (data == null) {
            throw new IllegalStateException("Order " + orderId + " not found");
        }
        Files.createDirectories(invoiceDir);
        Path file = invoiceFile(orderId);
        Files.writeString(file, InvoiceRenderer.render(data), StandardCharsets.UTF_8);
        return "Invoice saved: " + file.getFileName();
    }

    private String sendNotification(Long orderId) {
        log.info("SmartCart notification: Order {} payment confirmed", orderId);
        return "Notification logged: order " + orderId + " payment confirmed";
    }

    public Path invoiceFile(Long orderId) {
        return invoiceDir.resolve("invoice-" + orderId + ".html");
    }

    private InvoiceData invoiceData(Order o) {
        List<Line> lines = o.getItems().stream()
                .map(i -> new Line(i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.lineTotal()))
                .toList();
        List<String> address = new ArrayList<>();
        ShippingAddress a = o.getAddress();
        if (a != null) {
            address.add(a.getFullName());
            address.add(a.getLine1());
            if (a.getLine2() != null && !a.getLine2().isBlank()) {
                address.add(a.getLine2());
            }
            address.add(a.getCity() + ", " + a.getState() + " " + a.getPostalCode());
            address.add("Phone: " + a.getPhone());
        }
        Payment paid = payments.findByOrderIdAndStatus(o.getId(), PaymentStatus.SUCCEEDED).stream()
                .findFirst().orElse(null);
        String gateway = paid != null ? paid.getGateway().name() : o.getPaymentGateway().name();
        String paymentId = paid == null ? null
                : paid.getGatewayPaymentId() + (paid.getMethod() == null ? "" : " (" + paid.getMethod() + ")");
        return new InvoiceData(o.getId(), o.getCreatedAt(), o.getUser().getName(), o.getUser().getEmail(), address,
                lines, o.getSubtotal(), o.getShippingFee(), o.getTotalAmount(), gateway, paymentId);
    }

    private static String reason(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() != null ? root.getMessage() : e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }
}
